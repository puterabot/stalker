package era.put;

import era.put.base.Configuration;
import era.put.base.ConfigurationColombia;
import era.put.base.Util;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
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

    private void removeChromiumConfig() throws Exception {
        String homeDir = System.getenv("HOME");
        String command = "rm -rf " + homeDir + "/.cache/chromium " + homeDir + "/.config/chromium";
        Util.runOsCommand(command);
    }

    private void launchMwm() throws Exception {
        String command = "nohup mwm &";
        Util.runOsCommand(command);
    }

    /**
    Note this instance is not controlled by Selenium. The reason for controlling this browser instance
    out of Selenium is to bypass the protection posed by Cloudflare, that can detect when the browser
    is being run on test controlled mode and prevents entering the page.
    */
    private void launchInitialChromeBrowser() throws Exception {
        Configuration configuration = new ConfigurationColombia();
        ThreadFactory threadFactory = Util.buildThreadFactory("FindImageDupesDescriptorCalculator[%03d]");
        ExecutorService executorService = Executors.newFixedThreadPool(1, threadFactory);
        executorService.submit(() -> {
            String command = "nohup chrome " + configuration.getRootSiteUrl();
            try {
                Util.runOsCommand(command);
            } catch (Exception e) {

            }
        });

        String imageFilename = "./etc/02_chromeRestoreDontSignInButton.png";
        while (checkIfImageIsOnScreen(imageFilename) == null) {
            logger.info("Waiting for browser to start");
            Thread.sleep(1000);
        }
        logger.info("On sign in screen...");
        if (!clickOverImage(imageFilename, 72, 15)) {
            logger.error("Could not start new browser - check don't sign in button stage");
            System.exit(1);
        }
        Thread.sleep(2000);
    }

    private void clickCloudFlareButton() throws Exception {
        String imageFilename = "./etc/03_chromeCloudFlareButton.png";
        while (checkIfImageIsOnScreen(imageFilename) == null) {
            logger.info("Waiting for CloudFlare check screen");
            Thread.sleep(1000);
        }
        logger.info("On CloudFlare check screen...");
        if (!clickOverImage(imageFilename, 35, 38)) {
            logger.error("Could not start new browser - check CloudFlare button stage");
            System.exit(1);
        }
        Thread.sleep(2000);
    }

    private void closeMwmWindow() throws Exception {
        if (!clickOverImage("./etc/01_mwmWindowCorner.png", 15, 15)) {
            logger.error("Could not close initial browser window, stopping process");
            System.exit(1);
        }
        Thread.sleep(3000);
        if (!clickOverImage("./etc/06_mwmCloseWindowOption.png", 56, 12)) {
            logger.error("Could not close initial browser window, stopping process");
            System.exit(1);
        }
        Thread.sleep(2000);
    }

    private void clickMeAcceptButton() throws Exception {
        Robot robot = new Robot();

        Thread.sleep(2000);
        for (int i = 0; i < 6; i++) {
            robot.keyPress('\t');
            Thread.sleep(400);
            robot.keyRelease('\t');
            Thread.sleep(500);
        }
        robot.keyPress(' ');
        Thread.sleep(400);
        robot.keyRelease(' ');
    }

    public void doSeleniumBotStartup() throws Exception {
        removeChromiumConfig();
        while (checkIfImageIsOnScreen("./etc/01_mwmWindowCorner.png") == null) {
            launchMwm();
            Thread.sleep(5000);
        }

        launchInitialChromeBrowser();
        clickCloudFlareButton();
        clickMeAcceptButton();
        closeMwmWindow();
    }

    public void doSeleniumBotShutdown() throws Exception {
        //closeMwmWindow();
        String command = "pkill -9 Xnest";
        Util.runOsCommand(command);
    }
	    
    public static void main(String[] args) {
        try {
            MeAwtSessionSetupApp instance = new MeAwtSessionSetupApp();
            instance.doSeleniumBotStartup();
            instance.doSeleniumBotShutdown();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
