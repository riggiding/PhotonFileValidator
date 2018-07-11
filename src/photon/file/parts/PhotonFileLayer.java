/*
 * MIT License
 *
 * Copyright (c) 2018 Bonosoft
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package photon.file.parts;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;

/**
 * by bn on 01/07/2018.
 */
public class PhotonFileLayer {
    private float layerPositionZ;
    private float layerExposure;
    private float layerOffTimeSeconds;
    private int dataAddress;
    private int dataSize;
    private int unknown1;
    private int unknown2;
    private int unknown3;
    private int unknown4;

    private byte[] imageData;

    private byte[] packedLayerImage;

    private ArrayList<BitSet> islandRows;
    private int isLandsCount;
    private long pixels;

    private boolean extendsMargin;
    private PhotonFileHeader photonFileHeader;

    private PhotonFileLayer(PhotonInputStream ds) throws Exception {
        layerPositionZ = ds.readFloat();
        layerExposure = ds.readFloat();
        layerOffTimeSeconds = ds.readFloat();

        dataAddress = ds.readInt();
        dataSize = ds.readInt();

        unknown1 = ds.readInt();
        unknown2 = ds.readInt();
        unknown3 = ds.readInt();
        unknown4 = ds.readInt();
    }

    public int save(PhotonOutputStream os, int dataPosition) throws Exception {
        os.writeFloat(layerPositionZ);
        os.writeFloat(layerExposure);
        os.writeFloat(layerOffTimeSeconds);

        dataAddress = dataPosition;

        os.writeInt(dataAddress);
        os.writeInt(dataSize);

        os.writeInt(unknown1);
        os.writeInt(unknown2);
        os.writeInt(unknown3);
        os.writeInt(unknown4);

        return dataPosition + dataSize + 1;
    }

    public void saveData(PhotonOutputStream os) throws Exception {
        os.write(imageData, 0, dataSize);
        os.writeByte(0);
    }

    public static int getByteSize() {
        return 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4 + 4;
    }

    public ArrayList<BitSet> unpackImage(int resolutionX) {
        pixels = 0;
        resolutionX = resolutionX - 1;
        ArrayList<BitSet> unpackedImage = new ArrayList<>();
        BitSet currentRow = new BitSet();
        unpackedImage.add(currentRow);
        int x = 0;
        for (byte rle : imageData) {
            int length = rle & 0x7F;
            boolean color = (rle & 0x80) == 0x80;
            if (color) {
                pixels += length;
            }
            int endPosition = x + (length - 1);
            int lineEnd = Integer.min(endPosition, resolutionX);
            if (color) {
                currentRow.set(x, 1 + lineEnd);
            }
            if (endPosition > resolutionX) {
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                lineEnd = endPosition - (resolutionX + 1);
                if (color) {
                    currentRow.set(0, 1 + lineEnd);
                }
            }
            x = lineEnd + 1;
            if (x > resolutionX) {
                currentRow = new BitSet();
                unpackedImage.add(currentRow);
                x = 0;
            }
        }
        return unpackedImage;
    }

    private void calculate(ArrayList<BitSet> unpackedImage, ArrayList<BitSet> previousUnpackedImage, PhotonLayer photonLayer) {
        islandRows = new ArrayList<>();
        isLandsCount = 0;

        photonLayer.clear();

        for (int y = 0; y < unpackedImage.size(); y++) {
            BitSet currentRow = unpackedImage.get(y);
            BitSet prevRow = previousUnpackedImage != null ? previousUnpackedImage.get(y) : null;
            if (currentRow != null) {
                for (int x = 0; x < currentRow.length(); x++) {
                    if (currentRow.get(x)) {
                        if (prevRow == null || prevRow.get(x)) {
                            photonLayer.supported(x, y);
                        } else {
                            photonLayer.island(x, y);
                        }
                    }
                }
            }
        }

        photonLayer.reduce();

        isLandsCount = photonLayer.setIslands(islandRows);
    }

    public static List<PhotonFileLayer> readLayers(PhotonFileHeader photonFileHeader, byte[] file, int margin, IPhotonLoadProgress iPhotonLoadProgress) throws Exception {
        PhotonLayer photonLayer = new PhotonLayer(photonFileHeader.getResolutionX(), photonFileHeader.getResolutionY());

        List<PhotonFileLayer> layers = new ArrayList<>();


        try (PhotonInputStream ds = new PhotonInputStream(new ByteArrayInputStream(file, photonFileHeader.getLayersDefinitionOffsetAddress(), file.length))) {
            ArrayList<BitSet> previousUnpackedImage = null;
            for (int i = 0; i < photonFileHeader.getNumberOfLayers(); i++) {

                iPhotonLoadProgress.showInfo("Reading photon file layer " + i + "/" + photonFileHeader.getNumberOfLayers());

                PhotonFileLayer layer = new PhotonFileLayer(ds);
                layer.photonFileHeader = photonFileHeader;
                layer.imageData = Arrays.copyOfRange(file, layer.dataAddress, layer.dataAddress + layer.dataSize);

                ArrayList<BitSet> unpackedImage = layer.unpackImage(photonFileHeader.getResolutionX());

                if (margin > 0) {
                    layer.extendsMargin = layer.checkMagin(unpackedImage, margin);
                }

                layer.calculate(unpackedImage, previousUnpackedImage, photonLayer);

                layers.add(layer);
                if (previousUnpackedImage != null) {
                    previousUnpackedImage.clear();
                }
                previousUnpackedImage = unpackedImage;

                layer.packedLayerImage = photonLayer.packLayerImage();

            }
        }

        photonLayer.unLink();
        System.gc();

        // System.out.println("Layer ALL, " + String.format("%10d", ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))) + " MB");

        return layers;
    }

    public ArrayList<PhotonRow> getRows() {
        return PhotonLayer.getRows(packedLayerImage, photonFileHeader.getResolutionX());
    }

    public ArrayList<BitSet> getIslandRows() {
        return islandRows;
    }

    public int getIsLandsCount() {
        return isLandsCount;
    }

    public long getPixels() {
        return pixels;
    }

    public float getLayerPositionZ() {
        return layerPositionZ;
    }

    public float getLayerExposure() {
        return layerExposure;
    }

    public float getLayerOffTime() {
        return layerOffTimeSeconds;
    }

    public void setLayerExposure(float layerExposure) {
        this.layerExposure = layerExposure;
    }

    public void setLayerOffTimeSeconds(float layerOffTimeSeconds) {
        this.layerOffTimeSeconds = layerOffTimeSeconds;
    }

    public void unLink() {
        imageData = null;
        packedLayerImage = null;
        islandRows.clear();
        photonFileHeader = null;
    }

    public boolean doExtendMargin() {
        return extendsMargin;
    }

    private boolean checkMagin(ArrayList<BitSet> unpackedImage, int margin) {
        if (unpackedImage.size() > margin) {
            // check top margin rows
            for (int i = 0; i < margin; i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }
            // check bottom margin rows
            for (int i = unpackedImage.size() - margin; i < unpackedImage.size(); i++) {
                if (!unpackedImage.get(i).isEmpty()) {
                    return true;
                }
            }

            for (int i = margin; i < unpackedImage.size() - margin; i++) {
                BitSet row = unpackedImage.get(i);
                int nextBit = row.nextSetBit(0);
                if (nextBit >= 0 && nextBit < margin) {
                    return true;
                }
                nextBit = row.nextSetBit(photonFileHeader.getResolutionX() - margin);
                if (nextBit > photonFileHeader.getResolutionX() - margin) {
                    return true;
                }
            }

        }
        return false;
    }

}
