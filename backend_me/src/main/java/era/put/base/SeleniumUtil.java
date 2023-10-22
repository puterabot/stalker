package era.put.base;

import era.put.MeBotSeleniumApp;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openqa.selenium.By;
import org.openqa.selenium.JavascriptExecutor;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.TakesScreenshot;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;
import ru.yandex.qatools.ashot.AShot;
import ru.yandex.qatools.ashot.Screenshot;
import ru.yandex.qatools.ashot.shooting.ShootingStrategies;

public class SeleniumUtil {
    private static final Logger logger = LogManager.getLogger(SeleniumUtil.class);
    private static final int DRIVER = 2;
    private static final Random random = new Random();
    private static String CHROME_PROFILE_PATH; // Check default value from URL chrome://version

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            CHROME_PROFILE_PATH = properties.getProperty("chromium.config.path");
        } catch (Exception e) {
            CHROME_PROFILE_PATH = "/tmp/.chromium_config";
        }
    }


    public static void login(WebDriver d, Configuration c) {
        d.get(c.getRootSiteUrl());
        delay(10000);
        //closeDialogs(d);
    }

    public static void closeDialogs(WebDriver d) {
        delay(100);
        try {
            WebElement e = d.findElement(By.id("ue-accept-button-accept"));
            if (e != null) {
                e.click();
                delay(500);
            }
        } catch (Exception x) {
            logger.error(x);
        }
    }

    public static void delay(int ms) {
        try {
            Thread.sleep(ms);
        } catch ( Exception e ) {
            // Ignore
        }
    }

    public static void screenShot(WebDriver d, String s) {
        TakesScreenshot td = (TakesScreenshot) d;
        File fd = td.getScreenshotAs(OutputType.FILE);
        File copy = new File(s);
        try {
            FileUtils.copyFile(fd, copy);
        } catch (IOException e) {
            logger.error(e);
        }
    }

    public static void fullPageScreenShot(WebDriver d, String s) {
        Screenshot screenshot = new AShot().shootingStrategy(ShootingStrategies.viewportPasting(40)).takeScreenshot(d);
        try {
            ImageIO.write(screenshot.getImage(), "jpg", new File(s));
        } catch (Exception e) {
            logger.error(e);
        }
    }

    public static WebDriver initWebDriver(Configuration conf) {
        try {
            System.setProperty("webdriver.chrome.silentOutput", "false");
            System.setProperty("webdriver.chrome.driver", "/usr/local/chromedriver-linux64/chromedriver");

            if (DRIVER == 2) {
                ChromeOptions options = new ChromeOptions();
                options.addArguments("--user-data-dir=" + CHROME_PROFILE_PATH); // Not thread safe
                options.addArguments("--log-level=3");
                options.addArguments("--disable-extensions");
                options.addArguments("--silent");
                options.addArguments("--no-sandbox");
                options.addArguments("--disable-dev-shm-usage");

                if (conf.useHeadlessBrowser()) {
                    // This has been deprecated. Pending to investigate how to replace.
                    //options.setHeadless(true);
                    logger.warn("Headless mode not supported on Cloudflare protected sites :(");
                }

                WebDriver driver = new ChromeDriver(options);
                MeBotSeleniumApp.currentDrivers.add(driver);
                return driver;
            } else {
                Util.exitProgram("Firefox not supported");
            }
        } catch (Exception e) {
            logger.error(e);
            Util.exitProgram("Error creating web driver.");
        }
        return null;
    }

    public static void scrollDownPage(WebDriver d) {
        JavascriptExecutor js;
        js = (JavascriptExecutor)d;
        for (int i = 0; i < 4; i++) {
            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            delay(200);
        }
    }

    public static void closeWebDriver(WebDriver webDriver) {
        if (webDriver != null) {
            logger.info("Closing web driver.");
        } else {
            logger.info("Web driver already closed.");
        }
        //Util.printCurrentStackTrace();

        logger.info("Killing chrome processes on the OS");
        try {
            Runtime.getRuntime().exec("pkill -9 chrome");
        } catch (IOException e) {
            logger.error(e);
        }
    }

    public static void randomDelay(int minimum, int maximum) {
        delay(minimum + random.nextInt(maximum - minimum));
    }
}
