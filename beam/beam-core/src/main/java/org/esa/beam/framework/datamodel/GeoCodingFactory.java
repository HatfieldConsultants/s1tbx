package org.esa.beam.framework.datamodel;/*
 * Copyright (C) 2012 Brockmann Consult GmbH (info@brockmann-consult.de)
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

import com.bc.ceres.core.ProgressMonitor;
import com.bc.jexp.ParseException;
import org.esa.beam.framework.dataio.ProductSubsetDef;
import org.esa.beam.framework.dataop.barithm.BandArithmetic;
import org.esa.beam.util.ProductUtils;

import javax.media.jai.Interpolation;
import javax.media.jai.operator.CropDescriptor;
import javax.media.jai.operator.ScaleDescriptor;
import java.awt.Rectangle;
import java.awt.image.RenderedImage;
import java.io.IOException;

public class GeoCodingFactory {

    public static final String USE_ALTERNATE_PIXEL_GEO_CODING_PROPERTY = "beam.useAlternatePixelGeoCoding";

    public static BasicPixelGeoCoding createPixelGeoCoding(final Band latBand,
                                                           final Band lonBand,
                                                           final String validMask,
                                                           final int searchRadius) {
        if ("true".equals(System.getProperty(USE_ALTERNATE_PIXEL_GEO_CODING_PROPERTY))) {
            return new PixelGeoCoding(latBand, lonBand, validMask, searchRadius);
        }
        return new PixelGeoCoding2(latBand, lonBand, validMask);
    }

    public static BasicPixelGeoCoding createPixelGeoCoding(final Band latBand,
                                                           final Band lonBand,
                                                           final String validMask,
                                                           final int searchRadius,
                                                           ProgressMonitor pm) throws IOException {
        if ("true".equals(System.getProperty(USE_ALTERNATE_PIXEL_GEO_CODING_PROPERTY))) {
            return new PixelGeoCoding(latBand, lonBand, validMask, searchRadius, pm); // this is a special constructor
        }
        return new PixelGeoCoding2(latBand, lonBand, validMask);
    }

    static void copyReferencedRasters(String validMaskExpression,
                                      Scene sourceScene,
                                      Scene targetScene,
                                      ProductSubsetDef subsetDef) throws ParseException {
        final Product targetProduct = targetScene.getProduct();
        final RasterDataNode[] nodes = BandArithmetic.getRefRasters(validMaskExpression,
                                                                    sourceScene.getProduct());
        for (RasterDataNode node : nodes) {
            if (!targetProduct.containsRasterDataNode(node.getName())) {
                if (node instanceof TiePointGrid) {
                    TiePointGrid tpg = TiePointGrid.createSubset((TiePointGrid) node, subsetDef);
                    targetProduct.addTiePointGrid(tpg);
                }
                if (node instanceof Band) {
                    final Band sourceBand = (Band) node;
                    final Band band = createSubset(sourceBand, targetScene, subsetDef);
                    targetProduct.addBand(band);
                    setFlagCoding(band, sourceBand.getFlagCoding());
                }
            }
        }
    }

    static Band createSubset(Band srcBand, Scene destScene, ProductSubsetDef subsetDef) {
        Band band = new Band(srcBand.getName(),
                             srcBand.getDataType(),
                             destScene.getRasterWidth(),
                             destScene.getRasterHeight());
        ProductUtils.copyRasterDataNodeProperties(srcBand, band);
        band.setSourceImage(getSourceImage(subsetDef, srcBand));
        return band;
    }

    private static void setFlagCoding(Band band, FlagCoding flagCoding) {
        if (flagCoding != null) {
            final String flagCodingName = flagCoding.getName();
            final Product product = band.getProduct();
            if (!product.getFlagCodingGroup().contains(flagCodingName)) {
                addFlagCoding(product, flagCoding);
            }
            band.setSampleCoding(product.getFlagCodingGroup().get(flagCodingName));
        }
    }

    private static void addFlagCoding(Product product, FlagCoding flagCoding) {
        final FlagCoding targetFlagCoding = new FlagCoding(flagCoding.getName());

        targetFlagCoding.setDescription(flagCoding.getDescription());
        ProductUtils.copyMetadata(flagCoding, targetFlagCoding);
        product.getFlagCodingGroup().add(targetFlagCoding);
    }

    private static RenderedImage getSourceImage(ProductSubsetDef subsetDef, Band band) {
        RenderedImage sourceImage = band.getSourceImage();
        if (subsetDef != null) {
            final Rectangle region = subsetDef.getRegion();
            if (region != null) {
                float x = region.x;
                float y = region.y;
                float width = region.width;
                float height = region.height;
                sourceImage = CropDescriptor.create(sourceImage, x, y, width, height, null);
            }
            final int subSamplingX = subsetDef.getSubSamplingX();
            final int subSamplingY = subsetDef.getSubSamplingY();
            if (subSamplingX != 1 || subSamplingY != 1) {
                float scaleX = 1.0f / subSamplingX;
                float scaleY = 1.0f / subSamplingY;
                float transX = 0.0f;
                float transY = 0.0f;
                Interpolation interpolation = Interpolation.getInstance(Interpolation.INTERP_NEAREST);
                sourceImage = ScaleDescriptor.create(sourceImage, scaleX, scaleY, transX, transY, interpolation, null);
            }
        }
        return sourceImage;
    }
}