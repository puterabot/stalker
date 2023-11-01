package era.put.building;

import com.mongodb.BasicDBObject;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import era.put.MeBotSeleniumApp;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.MongoUtil;
import era.put.base.SeleniumUtil;
import era.put.base.Util;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;

public class ProfileAnalyzerRunnable implements Runnable {
    private static final Logger logger = LogManager.getLogger(ProfileAnalyzerRunnable.class);
    private final ConcurrentLinkedQueue<Integer> availableProfileComputeElements;
    private final int id;
    private PrintStream out;
    private final Configuration configuration;
    //private int screenshootCounter = 0;

    public ProfileAnalyzerRunnable(ConcurrentLinkedQueue<Integer> availableProfileComputeElements, int id, Configuration configuration) {
        this.availableProfileComputeElements = availableProfileComputeElements;
        this.id = id;
        this.configuration = configuration;
    }

    private int
    monthIndex(String name) {
        String[] enNames = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        String[] esNames = {"ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic"};

        if (name == null || name.length() < 3) {
            return -1;
        }

        for (int i = 0; i < 12; i++) {
            if (name.toLowerCase().startsWith(enNames[i])
            || name.toLowerCase().startsWith(esNames[i])) {
                return i + 1;
            }
        }

        return -1;
    }

    private Date
    importDate(WebDriver d, String label, Configuration c) {
        try {
            int year;
            int month;
            int day;
            int hour = 0;
            int minute = 0;
            int second = 0;

            String date;
            String time;
            StringTokenizer parser = new StringTokenizer(label, ",\n");
            if (parser.countTokens() < 1 || parser.countTokens() > 2) {
                out.println("Cannot process date [" + label + "] on " + d.getCurrentUrl());
                return null;
            }

            // Process date
            date = parser.nextToken();

            StringTokenizer sup = new StringTokenizer(date, " \t");
            if (sup.countTokens() != 3) {
                out.println("Cannot process date [" + label + "] on " + d.getCurrentUrl());
                return null;
            }
            day = Integer.parseInt(sup.nextToken());
            month = monthIndex(sup.nextToken());
            if (month < 0) {
                out.println("Cannot process month from [" + label + "] on " + d.getCurrentUrl());
                return null;
            }
            year = Integer.parseInt(sup.nextToken());

            // Process time
            if (parser.hasMoreTokens()) {
                time = parser.nextToken();
                StringTokenizer sub = new StringTokenizer(time, ": ");
                if (sub.countTokens() < 2 || sub.countTokens() > 3) {
                    out.println("Cannot process time [" + label + "] on " + d.getCurrentUrl());
                    return null;
                }
                hour = Integer.parseInt(sub.nextToken().trim());
                minute = Integer.parseInt(sub.nextToken().trim());
                if (sub.hasMoreTokens()) {
                    second = Integer.parseInt(sub.nextToken().trim());
                }
            }

            ZonedDateTime zdt = ZonedDateTime.of(year, month, day, hour, minute, second, 0, ZoneId.of(c.getTimeZone()));
            return java.util.Date.from(zdt.toInstant());
        } catch (Exception e) {
            logger.error(e);
            out.println("Cannot process date [" + label + "] on " + d.getCurrentUrl());
            return null;
        }
    }

    private String extractPhoneNumber(WebDriver d) {
        WebElement tel;
        try {
            tel = d.findElement(By.className("fog-tel-stats"));
            if (tel == null || tel.getText() == null || tel.getText().isEmpty()) {
                tel = d.findElement(By.className("tel-no-prepayment"));
                if (tel == null || tel.getText() == null || tel.getText().isEmpty()) {
                    out.println("No telephone number at: " + d.getCurrentUrl());
                    d.quit();
                    endThread(101);
                    return null;
                }
            }
        } catch (Exception e) {
            try {
                tel = d.findElement(By.className("tel-no-prepayment"));
                if (tel == null || tel.getText() == null || tel.getText().isEmpty()) {
                    out.println("No telephone number at: " + d.getCurrentUrl());
                    d.quit();
                    endThread(101);
                    return null;
                }
            } catch (Exception e2) {
                return null;
            }
        }
        return tel.getText();
    }

    private Date
    extractDate(WebDriver d, Configuration c) {
        WebElement date;
        try {
            date = d.findElement(By.className("title-left"));
        } catch (NoSuchElementException e) {
            // There is one of two cases: post is no longer available or post does not have a date field
            return null;
        }
        List<WebElement> children = date.findElements(By.tagName("span"));
        if (children.size() != 4) {
            out.println("Wrong children number " + children.size() + " at: " + d.getCurrentUrl());
            d.quit();
            endThread(102);
        }
        return importDate(d, children.get(3).getText(), c);
    }

    private boolean
    extractWhatsappPromise(WebDriver d) {
        WebElement w;
        try {
            w = d.findElement(By.className("fog-whatsapp-stats"));
            return w != null;
        } catch (NoSuchElementException e) {
            return false;
        }
    }

    private String
    extractDescription(WebDriver d) {
        WebElement w;
        try {
            w = d.findElement(By.className("description-ad"));
            if (w == null) {
                return null;
            }
            return w.getText();
        } catch (NoSuchElementException e) {
            return null;
        }
    }

    private Set<String>
    extractImages(WebDriver d) {
        SeleniumUtil.scrollDownPage(d);
        //int sc = screenshootCounter % 100;
        //String msg = String.format("/tmp/screenshot%03d.jpg", sc);
        //Util.fullPageScreenShot(d, msg);
        //screenshootCounter++;

        List<WebElement> wl;
        try {
            wl = d.findElements(By.className("item-img-box"));
            if (wl == null || wl.isEmpty()) {
                // Post without images
                return null;
            }

            Set<String> l = new TreeSet<>();
            for (WebElement w: wl) {
                WebElement img = w.findElement(By.tagName("img"));
                if (img != null) {
                    String attr = img.getAttribute("src");
                    if (attr == null || attr.isEmpty()) {
                        out.println("ERROR: Image can not be null at " + d.getCurrentUrl());
                        return null;
                    }
                    l.add(attr);
                }
            }
            return l;
        } catch (NoSuchElementException e) {
            out.println("Fatal error importing images from " + d.getCurrentUrl());
            d.quit();
            endThread(105);
            return null;
        }
    }

    private Document getOrCreateProfileFromPhoneNumber(
        WebDriver d, MongoCollection<Document> profile, String phone) {
        Document filter = new Document().append("p", phone);
        try {
            MongoCursor<Document> cursor = profile.find(filter).iterator();
            if (!cursor.hasNext()) {
                profile.insertOne(filter);
                cursor = profile.find(filter).iterator();
                if (!cursor.hasNext()) {
                    out.println("ERROR: Can not retrieve or create profile for phone " + phone);
                    out.println("ERROR processing " + d.getCurrentUrl());
                    d.quit();
                    endThread(106);
                    return null;
                }
            }
            return cursor.next();
        } catch (Exception e) {
            logger.error(e);
            return null;
        }
    }

    private Document getOrCreateImageFromUrl(
        WebDriver d, MongoCollection<Document> image, String url) {
        Date md = ImageAnalyser.getDateFromUrl(url, configuration);
        Document newDocument = new Document().append("url", url);
        if (md != null) {
            newDocument.append("md", md);
        }
        MongoCursor<Document> cursor = image.find(newDocument).iterator();
        if (!cursor.hasNext()) {
            image.insertOne(newDocument.append("p", new ArrayList<>()));
            cursor = image.find(newDocument).iterator();
            if (!cursor.hasNext()) {
                out.println("ERROR: Can not retrieve or create image for url " + url);
                out.println("ERROR processing " + d.getCurrentUrl());
                d.quit();
                endThread(107);
                return null;
            }
        }

        return cursor.next();
    }

    private boolean removePostIfNonExistent(WebDriver d, Document p, MongoCollection<Document> post) {
        try {
            WebElement e = d.findElement(By.id("http-error"));
            if (e != null) {
                out.println("Removing non available post " + d.getCurrentUrl());
                Document filter = new Document().append("_id", p.get("_id"));
                post.deleteOne(filter);
                return true;
            }
            // Page has info, skip and does nothing
            return false;
        } catch (Exception e) {
            // Page has info, skip and does nothing
            return false;
        }
    }

    private String extractPhoneFromDescription(WebDriver d, String description) {
        // TEST WITH https://co.mileroticos.com/escorts/3185621170-ricas-vaginas-humedas-y-culos-apretaditos-casi-gratis/15217905/
        // Not handling numbers with repeated digits like 3185621170
        out.println("Trying to extract phone numbers from description at " + d.getCurrentUrl());

        description = description
            .replace("(", "")
            .replace(")", "")
            .replace("-", "")
            .replace("+ 57", "")
            .replace("+57", "");

        TreeSet<String> phoneCandidates = new TreeSet<>();
        boolean digitMode = false;
        StringBuilder currentStack = new StringBuilder();
        for (int i = 0; i < description.length(); i++) {
            char c = description.charAt(i);
            if (Character.isSpaceChar(c)) {
                continue;
            }

            if (Character.isDigit(c)) {
                if (digitMode) {
                    currentStack.append(c);
                    if (i == description.length() - 1) {
                        String phoneCandidate = currentStack.toString();
                        if (!phoneCandidate.isEmpty()) {
                            phoneCandidates.add(phoneCandidate);
                        }
                    }
                } else {
                    digitMode = true;
                    currentStack = new StringBuilder();
                    currentStack.append(c);
                }
            } else {
                String phoneCandidate = currentStack.toString();
                if (digitMode && !phoneCandidate.isEmpty()) {
                    phoneCandidates.add(phoneCandidate);
                    digitMode = false;
                }
            }
        }

        // Analyze candidate set
        TreeSet<String> validPhones = new TreeSet<>(); // Now handling only Colombia phones
        for (String s: phoneCandidates) {
            // Note: this is a particular logic for phones at Colombia, will not scale to other countries
            if (s.length() == 10 && s.startsWith("3")) {
                validPhones.add(s);
            }
        }

        if (validPhones.size() == 1) {
            return validPhones.first();
        } else if (validPhones.size() > 1) {
            out.println("Check post with multiple phones! " + d.getCurrentUrl());
            for (String v: validPhones) {
                out.println("  - " + v);
            }
        }

        return null;
    }

    private void
    processProfilePage(
            WebDriver d,
            Document postDocument,
            MongoConnection mongoConnection,
            Configuration c) {
        SeleniumUtil.closeDialogs(d);
        // Extract post date
        Date date = extractDate(d, c);
        if (date == null) {
            if (!removePostIfNonExistent(d, postDocument, mongoConnection.post)) {
                out.println("Skipping post with no date " + d.getCurrentUrl());
            }
            return;
        }
        // Update date only if it is previous to download or previous publish date (posts are republished by system)

        // Extract profile phone number
        String phone = extractPhoneNumber(d);

        boolean whatsappPromiseFlag = extractWhatsappPromise(d);

        String description = extractDescription(d);

        Set<String> imageUrls = extractImages(d);

        if (imageUrls == null) {
            out.println("SKIP POST WITH NO IMAGES " + d.getCurrentUrl());
            skipProfile(postDocument, mongoConnection.post);
            return;
        }

        boolean hasDescription = description != null && !description.isEmpty();

        if (phone == null && hasDescription) {
            phone = extractPhoneFromDescription(d, description);
        }

        boolean hasPhone = (phone != null) && !phone.isEmpty();

        if (!hasPhone) {
            out.println("SKIP POST WITH NO TEL: " + d.getCurrentUrl());
            skipProfile(postDocument, mongoConnection.post);
        } else {
            if (description != null) {
                int n = Math.min(60, description.length());
                out.println("TEL: " + phone);
                out.println("  - Source url: " + d.getCurrentUrl());
                out.println("  - Date: " + date);
                out.println("  - Whatsapp promise: " + (whatsappPromiseFlag ? "yes" : "no"));
                out.println("  - Description: " + (hasDescription ? description.substring(0, n) + "... " : "<empty>"));
                out.println("  - Images:" + imageUrls.size());
            }
        }

        // Write findings to database
        try {
            Document filter;
            Document newDocument;
            Document query;

            // Mark post as processed
            filter = new Document().append("_id", postDocument.get("_id"));
            newDocument = new Document().append("p", hasPhone);
            query = new Document().append("$set", newDocument);
            mongoConnection.post.updateOne(filter, query);

            // Add extracted date
            newDocument = new Document().append("md", date);
            query = new Document().append("$set", newDocument);
            mongoConnection.post.updateOne(filter, query);

            // Add integer id
            Integer iid = Util.extractIdFromPostUrl(d.getCurrentUrl());
            if (iid != null) {
                newDocument = new Document().append("i", iid);
                query = new Document().append("$set", newDocument);
                mongoConnection.post.updateOne(filter, query);
            }

            // Append description
            if (hasDescription) {
                newDocument = new Document().append("d", description);
                query = new Document().append("$set", newDocument);
                mongoConnection.post.updateOne(filter, query);
            }

            // Set whatsappPromiseFlag
            newDocument = new Document().append("w", whatsappPromiseFlag);
            query = new Document().append("$set", newDocument);
            mongoConnection.post.updateOne(filter, query);

            // Create profile
            if (hasPhone) {
                Document profileObject = getOrCreateProfileFromPhoneNumber(d, mongoConnection.profile, phone);

                if (profileObject == null) {
                    return;
                }

                // Define creation date
                newDocument = new Document().append("t", new Date());
                query = new Document().append("$set", newDocument);
                mongoConnection.profile.updateOne(profileObject, query);

                // Create images
                for (String img: imageUrls) {
                    Document imageObject = getOrCreateImageFromUrl(d, mongoConnection.image, img);
                    if (imageObject == null) {
                        continue;
                    }

                    // Maintain relationship to user profile
                    if (imageObject.get("u") != null) {
                        if (imageObject.get("u") instanceof ObjectId userIdFromDatabase) {
                            if(userIdFromDatabase.toString().compareTo(profileObject.get("_id").toString()) != 0) {
                                out.println("ERROR: Image " + img + " cannot be associated to two different user profiles!");
                                out.println("  - Original user: " + userIdFromDatabase);
                                out.println("  - Incoming user: " + profileObject.get("_id").toString());
                                out.flush();
                                d.quit();
                                endThread(108);
                            }
                        } else {
                            out.println("ERROR: Image " + img + " has a user that is not an ObjectId but " + profileObject.get("u").getClass().getName());
                            out.flush();
                            d.quit();
                            endThread(109);
                        }
                    } else {
                        newDocument = new Document().append("u", profileObject.get("_id"));
                        query = new Document().append("$set", newDocument);
                        mongoConnection.image.updateOne(imageObject, query);
                    }

                    // Update relationship list to posts
                    if (imageObject.get("p") == null) {
                        out.println("ERROR: Image " + img + " has no post relationships array! ");
                        d.quit();
                        endThread(110);
                    }
                    if (!(imageObject.get("p") instanceof ArrayList)) {
                        out.println("ERROR: Image " + img + " has post relationships array of invalid type " + imageObject.get("p").getClass().getName());
                        d.quit();
                        endThread(111);
                    }
                    Object arrayCandidate = imageObject.get("p");
                    if (arrayCandidate == null) {
                        logger.error("Fatal: arrayCandidate is null");
                        Util.exitProgram("null arrayCandidate");
                    } else if (arrayCandidate instanceof ArrayList<?> genericList) {
                        ArrayList<Object> castedList = new ArrayList<>(genericList);
                        boolean imageExistsInArray = false;

                            for (Object o : castedList) {
                                if (!(o instanceof ObjectId)) {
                                    out.println("ERROR: Image " + img + " has post relationships array with elements of invalid type " + o.getClass().getName());
                                    d.quit();
                                    endThread(112);
                                }
                                ObjectId element;
                                assert o instanceof ObjectId;
                                element = (ObjectId) o;
                                if (element.equals(postDocument.get("_id"))) {
                                    imageExistsInArray = true;
                                    break;
                                }
                            }

                        if (!imageExistsInArray) {
                            Object element = postDocument.get("_id");
                            castedList.add(element);
                            Document o = new Document().append("_id", imageObject.get("_id"));
                            newDocument = new Document().append("p", castedList);
                            query = new Document().append("$set", newDocument);
                            mongoConnection.image.updateOne(o, query);
                        }
                    } else {
                        logger.error("Fatal: arrayCandidate of unsupported class " + arrayCandidate.getClass().getName());
                        Util.exitProgram("Wrong class on arrayCandidate");
                    }

                    // Download image! :)
                    imageObject = getOrCreateImageFromUrl(d, mongoConnection.image, img);
                    if (imageObject != null) {
                        ImageDownloader.downloadImageIfNeeded(imageObject, mongoConnection.image, out);
                    }
                }
            }
        } catch (Exception e) {
            out.println("Error writing info to database for " + d.getCurrentUrl());
            logger.error(e);
        }
    }

    private void skipProfile(Document p, MongoCollection<Document> post) {
        Document o = new Document().append("_id", p.get("_id"));
        Document newDocument = new Document().append("p", false);
        Document query = new Document().append("$set", newDocument);
        post.updateOne(o, query);
    }

    private PrintStream createPrintStream() throws Exception {
        return new PrintStream("./log/profileThread_" + id + ".log");
    }

    public void
    processPendingProfiles(Configuration c) {
        WebDriver webDriver = null;
        try {
            logger.info("Starting processPendingProfiles.");
            SeleniumUtil.delay(1000);
            webDriver = SeleniumUtil.initWebDriver(c);
            if (webDriver == null) {
                Util.exitProgram("Can not connect to web browser.");
                return;
            }
            SeleniumUtil.login(webDriver, c);
            out = createPrintStream();

            MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }

            Integer i;
            while ((i = availableProfileComputeElements.poll()) != null) {
                Document filter = new Document().append("i", i);
                Document p = mongoConnection.post.find(filter).first();
                if (p == null ) {
                    logger.info("Not processing post {}, post not found on database", p != null ? p.getString("url") : "null");
                    continue;
                }
                if ( p.get("p") != null) {
                    logger.info("Not processing post with id {} and url {}, post marked as processed", i, p != null ? p.getString("url") : "null");
                    continue;
                }

                String url = p.getString("url");
                if (url != null) {
                    webDriver.get(url);
                    if (SeleniumUtil.blockedPage(webDriver)) {
                        logger.error("ERROR: Page blocked! {}", url);
                        Document updateFilter = new Document("_id", new ObjectId(p.get("_id").toString()));
                        Document disableQuery = new Document("$set", new BasicDBObject("p", false));
                        mongoConnection.post.updateOne(updateFilter, disableQuery);
                        continue;
                    }
                    MeBotSeleniumApp.panicCheck(webDriver);

                    boolean check = errorCheck(webDriver);

                    if (check) {
                        logger.info("Disabling post {}, url not available", p.get("_id").toString());
                        Document updateFilter = new Document("_id", new ObjectId(p.get("_id").toString()));
                        Document query = new Document("$set", new BasicDBObject("p", false));
                        mongoConnection.post.updateOne(updateFilter, query);
                        SeleniumUtil.delay(500);
                    } else {
                        processProfilePage(webDriver, p, mongoConnection, c);
                    }
                }
            }
            SeleniumUtil.closeWebDriver(webDriver);
        } catch (Exception e) {
            MongoConnection mongoConnection = MongoUtil.connectWithMongoDatabase();
            if (mongoConnection == null) {
                out.println("ERROR: Can not create connection with MongoDB");
                endThread(200);
            }

            if (webDriver != null) {
                SeleniumUtil.closeWebDriver(webDriver);
                SeleniumUtil.delay(10000);
                logger.warn("There was an error on processing, restarting web driver");
                logger.warn(e);
            }
            processPendingProfiles(c);
        }
    }

    private static boolean errorCheck(WebDriver webDriver) {
        SeleniumUtil.delay(400);
        try {
            WebElement errorCheck = webDriver.findElement(By.className("error"));
            if ( errorCheck != null ) {
                return errorCheck.getText().contains("solicitada no existe") ||
                        errorCheck.getText().contains("El perfil que buscas no existe");
            }
        } catch (NoSuchElementException e) {
            return false;
        }
        return false;
    }

    private void endThread(int status) {
        try {
            out.println("ERROR: Ending thread " + Thread.currentThread().getName() + " - status " + status);
        } catch (Exception e) {
            logger.error(e);
        }
    }

    @Override
    public void run() {
        try {
            processPendingProfiles(configuration);
            out.println("= Ending profile thread " + Thread.currentThread().getName() + " =====");
            out.println("End timestamp: " + new Date());
            System.out.println("  - Ending profile thread " + Thread.currentThread().getName());
        } catch (Exception e) {
            logger.error(e);
            endThread(999);
        }
    }
}
