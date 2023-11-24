package era.put;

import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
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

    private Point checkIfImageIsOnScreen(String referenceImageFilename) throws Exception {
        RGBImage referenceImage = ImagePersistence.importRGB(new File(referenceImageFilename));

        Robot robot = new Robot();

        Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        BufferedImage screenshot = robot.createScreenCapture(screenRect);

        RGBImage screenshotImage = new RGBImage();
        AwtRGBImageRenderer.importFromAwtBufferedImage(screenshot, screenshotImage);

        Point p = imageInImage(screenshotImage, referenceImage);
        if (p == null) {
            logger.info("Image {} is not present on screen", referenceImageFilename);
        } else {
            logger.info("Image {} detected on ({}, {})", referenceImageFilename, p.getX(), p.getY());
            return p;
        }
        return null;
    }

    private boolean clickOverImage(String imageFilename, int dx, int dy) throws Exception {
        Point p = checkIfImageIsOnScreen(imageFilename);
        if (p == null) {
            return false;
        }
        Robot robot = new Robot();
        robot.mouseMove(dx + (int)p.getX(), dy + (int)p.getY());
        Thread.sleep(400);
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(200);
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
        return true;
    }

    private void removeChromiumConfig() {
    }

    private void launchMwm() {
    }

    private void launchInitialChromeBrowser() {
    }

    private void doSeleniumBotStartup() throws Exception {
        removeChromiumConfig();
        while (checkIfImageIsOnScreen("./etc/01_mwmWindowCorner.png") == null) {
            launchMwm();
            Thread.sleep(5000);
        }
        launchInitialChromeBrowser(); // Note this instance is not controlled by Selenium
        if (!clickOverImage("./etc/01_mwmWindowCorner.png", 6, 6)) {
            logger.error("Could not close initial browser window, stopping process");
            System.exit(1);
        }
    }

    public static void main(String[] args) {
        try {
            MeAwtSessionSetupApp instance = new MeAwtSessionSetupApp();
            instance.doSeleniumBotStartup();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
