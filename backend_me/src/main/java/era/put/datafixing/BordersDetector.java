package era.put.datafixing;

import vsdk.toolkit.common.VSDK;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.media.RGBPixel;

public class BordersDetector {
    private static final int BLACK_THRESHOLD = 6; // 2 is too low, 10 is too high
    private static final int WHITE_THRESHOLD = 10;

    private static boolean rowColorOver(RGBImage image, int y, RGBPixel targetColor, int threshold, ColorLogic colorLogic) {
        double rSum = 0.0;
        double gSum = 0.0;
        double bSum = 0.0;

        RGBPixel p = new RGBPixel();
        double dx = image.getXSize();
        for (int x = 0; x < image.getXSize(); x++) {
            image.getPixelRgb(x, y, p);
            rSum += VSDK.signedByte2unsignedInteger(p.r);
            gSum += VSDK.signedByte2unsignedInteger(p.g);
            bSum += VSDK.signedByte2unsignedInteger(p.b);
        }
        rSum /= dx;
        gSum /= dx;
        bSum /= dx;

        if (colorLogic == ColorLogic.BLACK_LOGIC) {
            return rSum > targetColor.r + threshold ||
                gSum > targetColor.g + threshold ||
                bSum > targetColor.b + threshold;
        } else {
            return rSum < targetColor.r - threshold ||
                gSum < targetColor.g - threshold ||
                bSum < targetColor.b - threshold;
        }
    }

    private static boolean columnColorOver(RGBImage image, int x, RGBPixel targetColor, int threshold, ColorLogic colorLogic) {
        double rSum = 0.0;
        double gSum = 0.0;
        double bSum = 0.0;

        RGBPixel p = new RGBPixel();
        double dy = image.getYSize();
        for (int y = 0; y < image.getYSize(); y++) {
            image.getPixelRgb(x, y, p);
            rSum += VSDK.signedByte2unsignedInteger(p.r);
            gSum += VSDK.signedByte2unsignedInteger(p.g);
            bSum += VSDK.signedByte2unsignedInteger(p.b);
        }
        rSum /= dy;
        gSum /= dy;
        bSum /= dy;

        if (colorLogic == ColorLogic.BLACK_LOGIC) {
            return rSum > targetColor.r + threshold ||
                    gSum > targetColor.g + threshold ||
                    bSum > targetColor.b + threshold;
        } else {
            return rSum < targetColor.r - threshold ||
                gSum < targetColor.g - threshold ||
                bSum < targetColor.b - threshold;
        }
    }

    /**
    A return of null means that fiven image does not have borders and does not need to be cropped. A non-null region
    of interest is the desired new crop to avoid empty borders.
    */
    public static RegionOfInterest searchForBorders(RGBImage image, RGBPixel referenceColor, ColorLogic colorLogic) {
        if (image == null || image.getXSize() <= 1 || image.getYSize() <= 1) {
            return null;
        }

        int threshold = colorLogic == ColorLogic.BLACK_LOGIC ? BLACK_THRESHOLD : WHITE_THRESHOLD;
        RegionOfInterest roi = new RegionOfInterest();
        roi.x0 = 0;
        roi.y0 = 0;
        roi.x1 = image.getXSize() - 1;
        roi.y1 = image.getYSize() - 1;
        boolean withRoi = false;

        int y;

        // First side: top
        for (y = 0; y < image.getYSize(); y++) {
            if (rowColorOver(image, y, referenceColor, threshold, colorLogic)) {
                break;
            }
        }

        if (y >= image.getYSize() - 1) {
            // All file is referenceColor case
            return null;
        }

        if (y > 0) {
            roi.y0 = y + 1;
            withRoi = true;
        }

        // Second side: top
        for (y = image.getYSize() - 1; y >= 0; y--) {
            if (rowColorOver(image, y, referenceColor, threshold, colorLogic)) {
                break;
            }
        }

        if (y < image.getYSize() - 1) {
            roi.y1 = y - 1;
            withRoi = true;
        }

        // Third side: left
        int x;
        for (x = 0; x < image.getXSize(); x++) {
            if (columnColorOver(image, x, referenceColor, threshold, colorLogic)) {
                break;
            }
        }

        if (x > 0) {
            roi.x0 = x + 1;
            withRoi = true;
        }

        // Fourth side: right
        for (x = image.getXSize() - 1; x >= 0; x--) {
            if (columnColorOver(image, x, referenceColor, threshold, colorLogic)) {
                break;
            }
        }

        if (x < image.getXSize() - 1) {
            roi.x1 = x - 1;
            withRoi = true;
        }

        // Close
        if (withRoi) {
            return roi;
        }
        return null;
    }
}
