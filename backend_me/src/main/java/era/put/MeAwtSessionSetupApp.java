package era.put;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.io.File;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import vsdk.toolkit.io.image.ImagePersistence;
import vsdk.toolkit.media.RGBPixel;
import vsdk.toolkit.media.RGBImage;
import vsdk.toolkit.render.awt.AwtRGBImageRenderer;
import java.awt.image.BufferedImage;

public class MeAwtSessionSetupApp {
    private static final Logger logger = LogManager.getLogger(MeAwtSessionSetupApp.class);

    private boolean pixelsAreDifferent(RGBPixel a, RGBPixel b) {
        return a.r != b.r || a.g != b.g || a.b != b.b;
    }
    private boolean containsAt(RGBImage haystack, RGBImage needle, int x0, int y0) {
        RGBPixel a = new RGBPixel();
        RGBPixel b = new RGBPixel();
        for (int y = 0; y < needle.getYSize(); y++) {
            for (int x = 0; x < needle.getXSize(); x++) {
                needle.getPixelRgb(x, y, a);
                haystack.getPixelRgb(x0 + x, y0 + y, b);
                if (pixelsAreDifferent(a, b)) {
                    return false;
                }
            }
        }
        return true;
    }

    private Point imageInImage(RGBImage haystack, RGBImage needle) {
        for (int y = 0; y < haystack.getYSize() - needle.getYSize(); y++) {
            for (int x = 0; x < haystack.getXSize() - needle.getXSize(); x++) {
                if (containsAt(haystack, needle, x, y)) {
                    return new Point(x, y);
                }
            }
        }
        return null;
    }

    private void checkMwm(String referenceImageFilename) throws Exception {
        RGBImage referenceImage = ImagePersistence.importRGB(new File(referenceImageFilename));

        Robot robot = new Robot();

        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage screenshot = robot.createScreenCapture(screenRect);

        RGBImage screenshotImage = new RGBImage();
        AwtRGBImageRenderer.importFromAwtBufferedImage(screenshot, screenshotImage);

        ImagePersistence.exportPNG(new File("/tmp/a.png"), screenshotImage);

        Point p = imageInImage(screenshotImage, referenceImage);
        if (p == null) {
            logger.info("Image is not present on screen");
        } else {
            logger.info("Image detected on ({}, {})", p.getX(), p.getY());
            robot.mouseMove((int)p.getX(), (int)p.getY());
        }
    }
    public static void main(String[] args) {
        try {
            MeAwtSessionSetupApp intance = new MeAwtSessionSetupApp();
            intance.checkMwm("./etc/mwm_window.png");
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
