package era.put.building;

import java.io.File;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TreeSet;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.openqa.selenium.By;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import era.put.base.Configuration;
import era.put.base.MongoConnection;
import era.put.base.Util;

public class ProfileAnalyzerRunnable implements Runnable {
    private ConcurrentLinkedQueue<Integer> availableProfileComputeElements;
    private int id;
    private PrintStream out;
    private Configuration c;

    public ProfileAnalyzerRunnable(ConcurrentLinkedQueue<Integer> availableProfileComputeElements, int id, Configuration c) {
        this.availableProfileComputeElements = availableProfileComputeElements;
        this.id = id;
        this.c = c;
    }

    private int
    monthIndex(String name) {
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};

        if (name == null || name.length() < 3) {
            return -1;
        }

        int i;
        for (i = 0; i < 12; i++) {
            if (name.toLowerCase().startsWith(names[i])) {
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
            e.printStackTrace();
            out.println("Cannot process date [" + label + "] on " + d.getCurrentUrl());
            return null;
        }
    }

    private String extractPhoneNumber(WebDriver d) {
        WebElement tel;
        try {
            tel = d.findElement(By.className("fog-tel"));
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
            w = d.findElement(By.className("fog-whatsapp"));
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

    private List<String>
    extractImages(WebDriver d) {

        Util.scrollDownPage(d);
        Util.fullPageScreenShot(d, "/tmp/screenshot.jpg");

        List<WebElement> wl;
        try {
            wl = d.findElements(By.className("item-img-box"));
            if (wl == null || wl.isEmpty()) {
                // Post without images
                return null;
            }

            List<String> l = new ArrayList<>();
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
    }


    private Document getOrCreateImageFromUrl(
        WebDriver d, MongoCollection<Document> image, String url) {
        Date md = ImageAnalyser.getDateFromUrl(url, c);
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

    private boolean removePostIfInexistent(WebDriver d, Document p, MongoCollection<Document> post) {
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
            out.println("**** -> " + s + " N: " + s.length());
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
            Document p,
            MongoConnection mongoConnection,
            Configuration c) {
        Util.closeDialogs(d);
        // Extract post date
        Date date = extractDate(d, c);
        if (date == null) {
            if (!removePostIfInexistent(d, p, mongoConnection.post)) {
                out.println("Skipping post with no date " + d.getCurrentUrl());
            }
            return;
        }
        // Update date only if it is previous to download or previous publish date (posts are republished by system)

        // Extract profile phone number
        String phone = extractPhoneNumber(d);

        boolean whatsappPromiseFlag = extractWhatsappPromise(d);

        String description = extractDescription(d);

        List<String> imageUrls = extractImages(d);

        if (imageUrls == null) {
            out.println("SKIP AFTER NULL IMAGES " + d.getCurrentUrl());
            skipProfile(p, mongoConnection.post);
            return;
        }

        boolean hasDescription = description != null && !description.isEmpty();

        if (phone == null && hasDescription) {
            phone = extractPhoneFromDescription(d, description);
        }

        boolean hasPhone = (phone != null) && !phone.isEmpty();

        if (!hasPhone) {
            out.println("No tel " + d.getCurrentUrl());
        } else {
            out.println("TEL: " + phone);
            out.println("  - Source url: " + d.getCurrentUrl());
            out.println("  - Date: " + date);
            out.println("  - Whatsapp promise: " + (whatsappPromiseFlag ? "yes" : "no"));
            out.println("  - Description: " + (hasDescription ? description.substring(0, 60) + "... " : "<empty>"));
            out.println("  - Images:" + imageUrls.size());
        }

        // Write findings to database
        try {
            Document filter;
            Document newDocument;
            Document query;

            // Mark post as processed
            filter = new Document().append("_id", p.get("_id"));
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

                    // Maintain relationship to user profile
                    if (imageObject.get("u") != null) {
                        if (imageObject.get("u") instanceof ObjectId) {
                            ObjectId userIdFromDatabase = (ObjectId) imageObject.get("u");
                            if(userIdFromDatabase != null && userIdFromDatabase.toString().compareTo(profileObject.get("_id").toString()) != 0) {
                                out.println("ERROR: Image " + img + " cannot be associated to two different user profiles!");
                                out.println("  - Original user: " + userIdFromDatabase.toString());
                                out.println("  - Incoming user: " + profileObject.get("_id").toString());
                                d.quit();
                                endThread(108);
                            }
                        } else {
                            out.println("ERROR: Image " + img + " has a user that is not an ObjectId but " + profileObject.get("u").getClass().getName());
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
                    ArrayList<Object> l = (ArrayList<Object>) imageObject.get("p");
                    boolean imageExistsInArray = false;
                    for (Object o: l) {
                        if (!(o instanceof ObjectId)) {
                            out.println("ERROR: Image " + img + " has post relationships array with elements of invalid type " + o.getClass().getName());
                            d.quit();
                            endThread(112);
                        }
                        ObjectId element = (ObjectId) o;
                        if (element.equals(p.get("_id"))) {
                            imageExistsInArray = true;
                            break;
                        }
                    }
                    if (!imageExistsInArray) {
                        l.add(p.get("_id"));
                        Document o = new Document().append("_id", imageObject.get("_id"));
                        newDocument = new Document().append("p", l);
                        query = new Document().append("$set", newDocument);
                        mongoConnection.image.updateOne(o, query);
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
            e.printStackTrace();
        }
    }

    private void skipProfile(Document p, MongoCollection<Document> post) {
        Document o = new Document().append("_id", p.get("_id"));
        Document newDocument = new Document().append("p", false);
        Document query = new Document().append("$set", newDocument);
        post.updateOne(o, query);
    }

    private PrintStream createPrintStream() throws Exception {
        return new PrintStream(new File("./log/profileThread_" + id + ".log"));
    }

    public void
    processPendingProfiles(Configuration c) {
        try {
            WebDriver d = Util.initWebDriver(c);
            Util.login(d, c);
            out = createPrintStream();

            MongoConnection mongoConnection = Util.connectWithMongoDatabase();
            if (mongoConnection == null) {
                return;
            }

            Integer i;
            while ((i = availableProfileComputeElements.poll()) != null) {
                Document filter = new Document().append("i", i);
                Document p = mongoConnection.post.find(filter).first();
                if (p == null || p.get("d") != null) {
                    continue;
                }

                String url = p.getString("url");
                if (url != null) {
                    d.get(url);
                    processProfilePage(d, p, mongoConnection, c);
                }
            }
            d.close();
        } catch (Exception e) {
            MongoConnection mongoConnection = Util.connectWithMongoDatabase();
            if (mongoConnection == null) {
                out.println("ERROR: Can not create connection with MongoDB");
                endThread(200);
            }
            processPendingProfiles(c);
        }
    }

    private void endThread(int status) {
        try {
            out.println("ERROR: Ending thread " + Thread.currentThread().getName() + " - status " + status);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void run() {
        try {
            processPendingProfiles(c);
            out.println("= Ending profile thread " + Thread.currentThread().getName() + " =====");
            out.println("End timestamp: " + new Date());
            System.out.println("  - Ending profile thread " + Thread.currentThread().getName());
        } catch (Exception e) {
            e.printStackTrace();
            endThread(999);
        }
    }
}
