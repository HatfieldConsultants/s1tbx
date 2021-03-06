/*
 * Copyright (C) 2014 by Array Systems Computing Inc. http://www.array.ca
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 3 of the License, or (at your option)
 * any later version.
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, see http://www.gnu.org/licenses/
 */
package org.esa.nest.gpf;

import com.bc.ceres.core.ProgressMonitor;
import edu.emory.mathcs.jtransforms.fft.DoubleFFT_1D;
import org.esa.beam.framework.datamodel.*;
import org.esa.beam.framework.gpf.Operator;
import org.esa.beam.framework.gpf.OperatorException;
import org.esa.beam.framework.gpf.OperatorSpi;
import org.esa.beam.framework.gpf.Tile;
import org.esa.beam.framework.gpf.annotations.*;
import org.esa.beam.util.ProductUtils;
import org.esa.snap.eo.Constants;
import org.esa.snap.gpf.*;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Estimate global azimuth offset using Enhanced Spectral Diversity (ESD) approach.
 * Perform azimuth shift for all bursts in a sub-swath with the azimuth offset above
 * using a frequency domain method.
 */

@OperatorMetadata(alias = "Azimuth-Offset-Estimation",
        category = "SAR Processing/SENTINEL-1",
        authors = "Jun Lu, Luis Veci",
        copyright = "Copyright (C) 2014 by Array Systems Computing Inc.",
        description = "Estimate global azimuth offset for the whole image", internal=true)
public class AzimuthOffsetEstimationOp extends Operator {

    @SourceProduct(alias = "source")
    private Product sourceProduct;

    @TargetProduct(description = "The target product which will use the master's grid.")
    private Product targetProduct = null;

    private int cHalfWindowWidth = 0;
    private int cHalfWindowHeight = 0;
    private int rowUpSamplingFactor = 0;
    private int colUpSamplingFactor = 0;
    private boolean isOffsetAvailable = false;
    private double azOffset = 0.0;
    private double noDataValue = -9999.0;
    private Sentinel1Utils su = null;
    private Sentinel1Utils.SubSwathInfo[] subSwath = null;
    private int subSwathIndex = 0;
    private String swathIndexStr = null;
    private String[] subSwathNames = null;
    private String[] polarizations = null;

    private int cWindowWidth = 11;
    private int cWindowHeight = 11;

    /**
     * Default constructor. The graph processing framework
     * requires that an operator has a default constructor.
     */
    public AzimuthOffsetEstimationOp() {
    }

    /**
     * Initializes this operator and sets the one and only target product.
     * <p>The target product can be either defined by a field of type {@link org.esa.beam.framework.datamodel.Product} annotated with the
     * {@link org.esa.beam.framework.gpf.annotations.TargetProduct TargetProduct} annotation or
     * by calling {@link #setTargetProduct} method.</p>
     * <p>The framework calls this method after it has created this operator.
     * Any client code that must be performed before computation of tile data
     * should be placed here.</p>
     *
     * @throws org.esa.beam.framework.gpf.OperatorException If an error occurs during operator initialisation.
     * @see #getTargetProduct()
     */
    @Override
    public void initialize() throws OperatorException {

        try {
            cHalfWindowWidth = cWindowWidth / 2;
            cHalfWindowHeight = cWindowHeight / 2;

            su = new Sentinel1Utils(sourceProduct);
            su.computeDopplerRate();
            subSwath = su.getSubSwath();

            subSwathNames = su.getSubSwathNames();
            if (subSwathNames.length != 1) {
                throw new OperatorException("Split product is expected.");
            } else {
                subSwathIndex = 1;//Integer.parseInt(subSwathNames[0].substring(subSwathNames[0].length()-1));
                swathIndexStr = subSwathNames[0].substring(2);
            }

            polarizations = su.getPolarizations();

            createTargetProduct();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        }
    }

    /**
     * Create target product.
     */
    private void createTargetProduct() {

        targetProduct = new Product(sourceProduct.getName(),
                sourceProduct.getProductType(),
                sourceProduct.getSceneRasterWidth(),
                sourceProduct.getSceneRasterHeight());

        ProductUtils.copyProductNodes(sourceProduct, targetProduct);

        final String[] bandNames = sourceProduct.getBandNames();
        for (String bandName : bandNames) {
            if (bandName.contains("_mst")) {
                ProductUtils.copyBand(bandName, sourceProduct, bandName, targetProduct, true);
            } else {
                final Band band = sourceProduct.getBand(bandName);
                final Band targetBand = new Band(bandName,
                        band.getDataType(),
                        targetProduct.getSceneRasterWidth(),
                        targetProduct.getSceneRasterHeight());

                targetBand.setUnit(band.getUnit());
                targetProduct.addBand(targetBand);
            }
        }

        targetProduct.setPreferredTileSize(512, subSwath[subSwathIndex - 1].linesPerBurst);
    }

    /**
     * Called by the framework in order to compute a tile for the given target band.
     * <p>The default implementation throws a runtime exception with the message "not implemented".</p>
     *
     * @param targetTileMap   The target tiles associated with all target bands to be computed.
     * @param targetRectangle The rectangle of target tile.
     * @param pm              A progress monitor which should be used to determine computation cancelation requests.
     * @throws org.esa.beam.framework.gpf.OperatorException
     *          If an error occurs during computation of the target raster.
     */
    @Override
     public void computeTileStack(Map<Band, Tile> targetTileMap, Rectangle targetRectangle, ProgressMonitor pm)
             throws OperatorException {

        final int x0 = targetRectangle.x;
        final int y0 = targetRectangle.y;
        final int w = targetRectangle.width;
        final int h = targetRectangle.height;
        //System.out.println("x0 = " + x0 + ", y0 = " + y0 + ", w = " + w + ", h = " + h);

        try {
            if (!isOffsetAvailable) {
                estimateOffset();
            }

            // perform azimuth shift using FFT
            final String[] bandNames = sourceProduct.getBandNames();
            for (String bandName : bandNames) {
                if (!bandName.contains("_slv")) {
                    continue;
                }

                final Band srcBand = sourceProduct.getBand(bandName);
                final Band tgtBand = targetProduct.getBand(bandName);
                final Tile srcTile = getSourceTile(srcBand, targetRectangle);
                final Tile tgtTile = targetTileMap.get(tgtBand);
                final float[] srcArray = (float[]) srcTile.getDataBuffer().getElems();
                final float[] tgtArray = (float[]) tgtTile.getDataBuffer().getElems();

                final double[] col = new double[2*h];
                final double[] phase = new double[2*h];
                final DoubleFFT_1D col_fft = new DoubleFFT_1D(h);

                computeShiftPhaseArray(azOffset, h, phase);

                for (int c = 0; c < w; c++) {
                    for (int r = 0; r < h; r++) {
                        col[2 * r] = srcArray[r * w + c];
                        col[2 * r + 1] = 0.0;
                    }

                    col_fft.complexForward(col);

                    multiplySpectrumByShiftFactor(col, phase);

                    col_fft.complexInverse(col, true);

                    for (int r = 0; r < h; r++) {
                        tgtArray[r * w + c] = (float)col[2 * r];
                    }
                }
            }

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException(getId(), e);
        } finally {
            pm.done();
        }
    }

    /**
     * Estimate azimuth offset using ESD approach.
     * @throws Exception The exception.
     */
    private synchronized void estimateOffset() throws Exception {

        if (isOffsetAvailable) {
            return;
        }

        final int[] overlapSizeArray = computeBurstOverlapSize();
        final int numOverlaps = overlapSizeArray.length;

        final StatusProgressMonitor status = new StatusProgressMonitor(numOverlaps,
                "Estimating azimuth offset... ");
        int tileCnt = 0;

        final ThreadManager threadManager = new ThreadManager();
        try {
            List<Double> azOffsetArray = new ArrayList<>(numOverlaps);

            for (int i = 0; i < numOverlaps; i++) {
                checkForCancellation();
                final int x0 = 0;
                final int y0 = subSwath[subSwathIndex - 1].linesPerBurst * (i + 1);
                final int w = subSwath[subSwathIndex - 1].samplesPerBurst;
                final int h = overlapSizeArray[i];

                final double tCycle =
                        subSwath[subSwathIndex - 1].linesPerBurst * subSwath[subSwathIndex - 1].azimuthTimeInterval;

                double sumSpectralSeparation = 0.0;
                for (int b = 0; b < subSwath[subSwathIndex - 1].numOfBursts; b++) {
                    for (int p = 0; p < subSwath[subSwathIndex - 1].samplesPerBurst; p++) {
                        sumSpectralSeparation += subSwath[subSwathIndex - 1].dopplerRate[b][p] * tCycle;
                    }
                }
                final double spectralSeparation = sumSpectralSeparation / (subSwath[subSwathIndex - 1].numOfBursts *
                        subSwath[subSwathIndex - 1].samplesPerBurst);

                final Thread worker = new Thread() {
                    @Override
                    public void run() {
                        try {
                            final Rectangle backwardRectangle = new Rectangle(x0, y0, w, h);
                            final Rectangle forwardRectangle = new Rectangle(x0, y0 - h, w, h);
                            final Band mBandI = getBand("_mst", "i_", swathIndexStr, polarizations[0]);
                            final Band mBandQ = getBand("_mst", "q_", swathIndexStr, polarizations[0]);
                            final Band sBandI = getBand("_slv", "i_", swathIndexStr, polarizations[0]);
                            final Band sBandQ = getBand("_slv", "q_", swathIndexStr, polarizations[0]);

                            final double azOffset = estimateAzOffsets(mBandI, mBandQ, sBandI, sBandQ,
                                    backwardRectangle, forwardRectangle, spectralSeparation);

                            synchronized(azOffsetArray) {
                                azOffsetArray.add(azOffset);
                            }
                        } catch (Throwable e) {
                            OperatorUtils.catchOperatorException("estimateOffset", e);
                        }
                    }
                };
                threadManager.add(worker);

                status.worked(tileCnt++);
            }

            // todo The following simple average should be replaced by weighted average using coherence as weight
            double sumAzOffset = 0.0;
            for (int i = 0; i < azOffsetArray.size(); i++) {
                sumAzOffset += azOffsetArray.get(i);
            }
            azOffset = sumAzOffset / numOverlaps;

            status.done();
            threadManager.finish();

        } catch (Throwable e) {
            OperatorUtils.catchOperatorException("estimateOffset", e);
        }

        isOffsetAvailable = true;
    }

    /**
     * Compute burst overlap size for all bursts in given sub-swath.
     * @return The burst overlap size array.
     */
    private int[] computeBurstOverlapSize() {

        final int numBursts = subSwath[subSwathIndex - 1].numOfBursts;
        int[] sizeArray = new int[numBursts - 1];

        for (int i = 0; i < numBursts - 1; i++) {
            final double endTime = subSwath[subSwathIndex - 1].burstLastLineTime[i];
            final double startTime = subSwath[subSwathIndex - 1].burstFirstLineTime[i+1];
            sizeArray[i] = (int)((endTime - startTime) / subSwath[subSwathIndex - 1].azimuthTimeInterval);
        }

        return sizeArray;
    }

    private double estimateAzOffsets(final Band mBandI, final Band mBandQ, final Band sBandI, final Band sBandQ,
                                       final Rectangle backwardRectangle, final Rectangle forwardRectangle,
                                       final double spectralSeparation) {

        final Tile mTileIBack = getSourceTile(mBandI, backwardRectangle);
        final Tile mTileQBack = getSourceTile(mBandQ, backwardRectangle);
        final Tile sTileIBack = getSourceTile(sBandI, backwardRectangle);
        final Tile sTileQBack = getSourceTile(sBandQ, backwardRectangle);
        final double[] mIBackArray = (double[]) mTileIBack.getDataBuffer().getElems();
        final double[] mQBackArray = (double[]) mTileQBack.getDataBuffer().getElems();
        final double[] sIBackArray = (double[]) sTileIBack.getDataBuffer().getElems();
        final double[] sQBackArray = (double[]) sTileQBack.getDataBuffer().getElems();

        final Tile mTileIFor = getSourceTile(mBandI, forwardRectangle);
        final Tile mTileQFor = getSourceTile(mBandQ, forwardRectangle);
        final Tile sTileIFor = getSourceTile(sBandI, forwardRectangle);
        final Tile sTileQFor = getSourceTile(sBandQ, forwardRectangle);
        final double[] mIForArray = (double[]) mTileIFor.getDataBuffer().getElems();
        final double[] mQForArray = (double[]) mTileQFor.getDataBuffer().getElems();
        final double[] sIForArray = (double[]) sTileIFor.getDataBuffer().getElems();
        final double[] sQForArray = (double[]) sTileQFor.getDataBuffer().getElems();

        final int arrayLength = mIBackArray.length;
        final double[] backIntReal = new double[arrayLength];
        final double[] backIntImag = new double[arrayLength];
        complexArrayMultiplication(mIBackArray, mQBackArray, sIBackArray, sQBackArray, backIntReal, backIntImag);

        final double[] forIntReal = new double[arrayLength];
        final double[] forIntImag = new double[arrayLength];
        complexArrayMultiplication(mIForArray, mQForArray, sIForArray, sQForArray, forIntReal, forIntImag);

        final double[] diffIntReal = new double[arrayLength];
        final double[] diffIntImag = new double[arrayLength];
        complexArrayMultiplication(backIntReal, backIntImag, forIntReal, forIntImag, diffIntReal, diffIntImag);

        double meanReal = 0.0;
        double meanImag = 0.0;
        for (int i = 0; i < arrayLength; i++) {
            meanReal += diffIntReal[i];
            meanImag += diffIntImag[i];
        }
        meanReal /= arrayLength;
        meanImag /= arrayLength;

        final double phase = Math.atan2(meanImag, meanReal);
        final double offset =
                phase / (2 * Math.PI * spectralSeparation * subSwath[subSwathIndex - 1].azimuthTimeInterval);

        return offset;
    }

    private void complexArrayMultiplication(final double[] realArray1, final double[] imagArray1,
                                            final double[] realArray2, final double[] imagArray2,
                                            final double[] realOutput, final double[] imagOutput) {

        final int arrayLength = realArray1.length;
        if (imagArray1.length != arrayLength || realArray2.length != arrayLength || imagArray2.length != arrayLength ||
                realOutput.length != arrayLength || imagOutput.length != arrayLength) {
            throw new OperatorException("Arrays of the same length are expected.");
        }

        for (int i = 0; i < arrayLength; i++) {
            realOutput[i] = realArray1[i] * realArray2[i] + imagArray1[i] * imagArray2[i];
            imagOutput[i] = imagArray1[i] * realArray2[i] - realArray1[i] * imagArray2[i];
        }
    }

    private Band getBand(final String suffix, final String prefix, final String swathIndexStr, final String polarization) {

        final String[] bandNames = sourceProduct.getBandNames();
        for (String bandName : bandNames) {
            if (bandName.contains(suffix) && bandName.contains(prefix) &&
                    bandName.contains(swathIndexStr) && bandName.contains(polarization)) {
                return sourceProduct.getBand(bandName);
            }
        }
        return null;
    }

    private static void computeShiftPhaseArray(final double shift, final int signalLength, final double[] phaseArray) {

        int k2;
        double phaseK;
        final double phase = -2.0 * Math.PI * shift / signalLength;
        final int halfSignalLength = (int) (signalLength * 0.5 + 0.5);

        for (int k = 0; k < signalLength; ++k) {
            if (k < halfSignalLength) {
                phaseK = phase * k;
            } else {
                phaseK = phase * (k - signalLength);
            }
            k2 = k * 2;
            phaseArray[k2] = Math.cos(phaseK);
            phaseArray[k2 + 1] = Math.sin(phaseK);
        }
    }

    private static void multiplySpectrumByShiftFactor(final double[] array, final double[] phaseArray) {

        int k2;
        double c, s;
        double real, imag;
        final int signalLength = array.length / 2;
        for (int k = 0; k < signalLength; ++k) {
            k2 = k * 2;
            c = phaseArray[k2];
            s = phaseArray[k2 + 1];
            real = array[k2];
            imag = array[k2 + 1];
            array[k2] = real * c - imag * s;
            array[k2 + 1] = real * s + imag * c;
        }
    }

    /**
     * The SPI is used to register this operator in the graph processing framework
     * via the SPI configuration file
     * {@code META-INF/services/org.esa.beam.framework.gpf.OperatorSpi}.
     * This class may also serve as a factory for new operator instances.
     *
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator()
     * @see org.esa.beam.framework.gpf.OperatorSpi#createOperator(java.util.Map, java.util.Map)
     */
    public static class Spi extends OperatorSpi {
        public Spi() {
            super(AzimuthOffsetEstimationOp.class);
        }
    }

}
