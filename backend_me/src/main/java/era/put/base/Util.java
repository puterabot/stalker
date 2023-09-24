package era.put.base;

import com.mongodb.BasicDBObject;
import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import era.put.MeBotSeleniumApp;
import era.put.building.ImageFileAttributes;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.bson.types.ObjectId;
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

import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class Util {
    private static final Logger logger = LogManager.getLogger(Util.class);
    private static int DRIVER = 2;
    private static String CHROME_PROFILE_PATH = "/home/jedilink/.config/chromium"; // From chrome://version/
    private static String MONGO_SERVER;
    private static String MONGO_USER;
    private static String MONGO_PASSWORD;
    private static String MONGO_DATABASE;
    private static int MONGO_PORT;
    private static final Random random = new Random();

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            MONGO_SERVER = properties.getProperty("mongo.server");
            MONGO_PORT = Integer.parseInt(properties.getProperty("mongo.port"));
            MONGO_USER = properties.getProperty("mongo.user");
            MONGO_PASSWORD = properties.getProperty("mongo.password");
            MONGO_DATABASE = properties.getProperty("mongo.database");
        } catch (Exception e) {
            MONGO_SERVER = "127.0.0.1";
            MONGO_PORT = 27017;
            MONGO_USER="root";
            MONGO_PASSWORD="putPasswordOnApplicationProperties";
            MONGO_DATABASE="scraper_me";
        }
    }

    public static MongoConnection connectWithMongoDatabase() {
        MongoConnection mongoConnection = new MongoConnection();
        try {
            CodecRegistry pojosTranslator = fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    fromProviders(PojoCodecProvider.builder().automatic(true).build()));
            ConnectionString cs = new ConnectionString("mongodb://" + MONGO_USER + ":" + MONGO_PASSWORD + "@" + MONGO_SERVER + ":" + MONGO_PORT);
            MongoClientSettings settings = MongoClientSettings.builder()
                .codecRegistry(pojosTranslator)
                .applyConnectionString(cs)
                .build();
            MongoClient mongoClient = MongoClients.create(settings);
            MongoDatabase database = mongoClient.getDatabase(MONGO_DATABASE);
            mongoConnection.profile = database.getCollection("profile");
            mongoConnection.profileInfo = database.getCollection("profileInfo");
            mongoConnection.post = database.getCollection("post");
            mongoConnection.image = database.getCollection("image");
        } catch (Exception e) {
            logger.error("ERROR connecting to mongo database");
            return null;
        }
        return mongoConnection;
    }

    public static void login(WebDriver d, Configuration c) {
        d.get(c.getRootSiteUrl());
        delay(10000);
        closeDialogs(d);
    }

    public static void closeDialogs(WebDriver d) {
        delay(100);
        try {
            WebElement e = d.findElement(By.id("ue-accept-button-accept"));
            if (e != null) {
                e.click();
                Util.delay(500);
            }
        } catch (Exception x) {
        }
    }

    public static void ramdomDelay(int minimum, int maximum) {
        delay(minimum + random.nextInt(maximum - minimum));
    }

    public static void exitProgram(String msg) {
        logger.info("ABORTING PROGRAM. Reason: [" + msg + "]");
        printCurrentStackTrace();
        System.exit(666);
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
        }
    }

    public static void fullPageScreenShot(WebDriver d, String s) {
        Screenshot screenshot = new AShot().shootingStrategy(ShootingStrategies.viewportPasting(40)).takeScreenshot(d);
        try {
            ImageIO.write(screenshot.getImage(), "jpg", new File(s));
        } catch (Exception e) {

        }
    }

    public static Integer extractIdFromPostUrl(String url) {
        StringTokenizer parser = new StringTokenizer(url, "/");
        String lastToken = null;
        while (parser.hasMoreTokens()) {
            lastToken = parser.nextToken();
        }
        try {
            return Integer.parseInt(lastToken);
        } catch (Exception e) {
            return null;
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
                    options.setHeadless(true);
                }
                WebDriver driver = new ChromeDriver(options);
                MeBotSeleniumApp.currentDrivers.add(driver);
                return driver;
            } else {
                exitProgram("Firefox not supported");
            }
        } catch (Exception e) {
            logger.error(e);
            Util.exitProgram("Error creating web driver.");
        }
        return null;
    }

    public static ImageFileAttributes getImageAttributes(Document i) {
        Object o = i.get("a");
        if (!(o instanceof Document)) {
            return null;
        }
        Document d = (Document) o;
        return ImageFileAttributes.fromDocument(d);
    }

    public static ObjectId getImageParentProfileId(Document i) {
        Object parentPivotO = i.get("u");
        if (!(parentPivotO instanceof ObjectId)) {
            return null;
        }
        return (ObjectId)parentPivotO;
    }

    public static Document getImageParentProfile(Document i, MongoCollection<Document> profile) {
        ObjectId id = getImageParentProfileId(i);
        if (id == null) {
            return null;
        }
        Document filter = new Document("_id", id);
        return profile.find(filter).first();
    }

    public static boolean fileDiff(String a, String b) throws Exception {
        BufferedInputStream fisa = new BufferedInputStream(new FileInputStream(new File(a)));
        BufferedInputStream fisb = new BufferedInputStream(new FileInputStream(new File(b)));
        while (fisa.available() > 0) {
            int da = fisa.read();
            int db = fisb.read();
            if (da != db) {
                fisa.close();
                fisb.close();
                return false;
            }
        }
        fisa.close();
        fisb.close();
        return true;
    }

    public static Document getImageFromPostId(ObjectId postId, MongoCollection<Document> image) {
        Document i = image.find(new Document("p0", postId)).first();
        if (i == null) {
            // Try from p array
            ArrayList<Document> conditions = new ArrayList<>();
            Document noP0 = new Document("p0", new BasicDBObject("$exists", false));
            conditions.add(noP0);
            Document equalsInsideArray = new Document("$eq", postId);
            Document elemMatch = new Document("$elemMatch", equalsInsideArray);
            Document arraySearch = new Document("p", elemMatch);
            conditions.add(arraySearch);
            Document query = new Document("$and", conditions);
            return image.find(query).first();
        }
        return i;
    }

    public static Date getDateFromMdOrT(Document p) {
        Date firstPostDate;
        firstPostDate = p.getDate("md");
        if (firstPostDate == null) {
            p.getDate("t");
        }
        return firstPostDate;
    }

    public static void scrollDownPage(WebDriver d) {
        JavascriptExecutor js;
        js = (JavascriptExecutor)d;
        for (int i = 0; i < 4; i++) {
            js.executeScript("window.scrollTo(0, document.body.scrollHeight)");
            delay(200);
        }
    }

    public static void printCurrentStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        logger.info("Stack trace for thread [" + Thread.currentThread().getName() + "]:");
        for (StackTraceElement element : stackTrace) {
            logger.info(element.getClassName() + "." + element.getMethodName()
                    + "(" + element.getFileName() + ":" + element.getLineNumber() + ")");
        }
    }
    public static void closeWebDriver(WebDriver webDriver) {
        if (webDriver != null) {
            logger.info("Closing web driver.");
        } else {
            logger.info("Web driver already closed.");
        }
        printCurrentStackTrace();
        Util.closeWebDriver(webDriver);
    }
}
