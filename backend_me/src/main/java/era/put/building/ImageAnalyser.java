package era.put.building;

import com.mongodb.client.MongoCollection;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bson.Document;
import era.put.base.Configuration;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.StringTokenizer;
import org.bson.types.ObjectId;

public class ImageAnalyser {
    private static final Logger logger = LogManager.getLogger(ImageAnalyser.class);
    private static String removeCommentFromFileToolReport(String l) {
        int index = l.indexOf("comment: \"");
        if (index >= 0) {
            String tail = l.substring(index + 10);
            int endIndex = tail.indexOf("\"");
            String r = l.substring(0, index);
            if (index + endIndex + 12 < l.length()) {
                r += l.substring(index + endIndex + 12);
            }
            return r;
        }
        return l;
    }

    private static boolean processLineFromFileToolReport(String l, FileToolReport report, String filename) {
        if (l == null || l.length() == 0) {
            return false;
        }

        int index = l.indexOf(": ");
        if (index <= 0) {
            System.err.println("ERROR, bad file info: " + l + " at " + filename);
            return false;
        }

        String tail = removeCommentFromFileToolReport(l.substring(index + 2));
        StringTokenizer parser = new StringTokenizer(tail, ",");
        for (int i = 0; parser.hasMoreTokens(); i++) {
            String token = parser.nextToken().trim();
            if (i == 0 && !token.startsWith("JPEG") && !token.startsWith("ERROR: JPEG")) {
                System.err.println("ERROR, not JPEG format " + token + " at " + filename);
                return false;
            } else if (i == 0) {
            } else if (i == 1 && !token.startsWith("JFIF")) {
                //System.err.println("WARNING, not JFIF standard " + token + " at " + filename);
                //return false;
            } else if (i == 1) {
            } else if (token.startsWith("density")
                    || token.startsWith("segment length")
                    || token.startsWith("resolution")
                    || token.startsWith("progressive")
                    || token.startsWith("aspect ratio")
                    || token.startsWith("baseline")
                    || token.startsWith("Exif Standard")
                    || token.startsWith("little-endian")
                    || token.startsWith("big-endian")
                    || token.startsWith("direntries")
                    || token.startsWith("orientation")
                    || token.startsWith("xresolution")
                    || token.startsWith("yresolution")
                    || token.startsWith("description")
                    || token.startsWith("manufacturer")
                    || token.startsWith("model")
                    || token.startsWith("datetime")
                    || token.startsWith("software")
                    || token.startsWith("comment")
                    || token.startsWith("height")
                    || token.startsWith("bps")
                    || token.startsWith("PhotometricInterpretation")
                    || token.startsWith("GPS-Data")
                    || token.startsWith("hostcomputer")
                    || token.startsWith("copyright")
                    || token.startsWith("thumbnail")
                    || token.startsWith("compression")
                    || token.startsWith("LTD")
                    || token.startsWith("width")
                    || token.startsWith("extended sequential")) {
            } else if (token.startsWith("precision ")) {
                String p = token.substring(10);
                if (!p.equals("8")) {
                    System.err.println("ERROR, precision " + p + " not supported at " + filename);
                    return false;
                }
            } else if (token.startsWith("frames ")) {
                String f = token.substring(7);
                if (!f.startsWith("8") && !f.startsWith("4") && !f.startsWith("3") && !f.startsWith("1")) {
                    System.err.println("ERROR, frames " + f + " not supported at " + filename);
                    return false;
                }
            } else {
                // A size expected
                int xIndex = token.indexOf("x");
                if (xIndex < 1) {
                    continue;
                }
                StringTokenizer nparser = new StringTokenizer(token, "x");
                if (nparser.countTokens() != 2) {
                    continue;
                }
                try {
                    int x = Integer.parseInt(nparser.nextToken());
                    int y = Integer.parseInt(nparser.nextToken());
                    report.lastX = x;
                    report.lastY = y;
                    if (x > report.xMax) {
                        //System.out.print("X[" + x + "]");
                        //System.err.println("X[" + x + "] " + filename);
                        report.xMax = x;
                    }
                    if (y > report.yMax) {
                        //System.out.print("Y[" + y + "]");
                        //System.err.println("Y[" + y + "] " + filename);
                        report.yMax = y;
                    }
                } catch (NumberFormatException e) {
                }
            }
        }
        return true;
    }

    public static boolean getImageDataFromFileTool(String filename, FileToolReport report) throws Exception {
        File fd = new File(filename);

        if (!fd.exists()) {
            report.nonExistentCount++;
            return false;
        } else if (!fd.canRead()) {
            report.accessDeniedCount++;
            return false;
        }

        String[] command = {"file", filename};
        Process p = Runtime.getRuntime().exec(command);
        BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));

        String fileInfo = br.readLine();
        if (!processLineFromFileToolReport(fileInfo, report, filename)) {
            report.badDataCount++;
            return false;
        }

        report.successCount++;
        return true;
    }

    public static Date getDateFromUrl(String url, Configuration c) {
        String[] starts = {
            "https://static1.mileroticos.com/photos/d/",
            "https://static1.mileroticos.com/photos/t1/"
        };
        String start = null;

        for (String currentStart: starts) {
            if (url.startsWith(currentStart)) {
                start = currentStart;
                break;
            }
        }

        if (start == null) {
            return null;
        }

        String tail = url.substring(start.length());
        StringTokenizer parser = new StringTokenizer(tail, "/");
        if (parser.countTokens() < 3) {
            return null;
        }
        int year = Integer.parseInt(parser.nextToken());
        int month = Integer.parseInt(parser.nextToken());
        int day = Integer.parseInt(parser.nextToken());
        ZonedDateTime zdt = ZonedDateTime.of(year, month, day, 0, 0, 0, 0, ZoneId.of(c.getTimeZone()));
        return java.util.Date.from(zdt.toInstant());
    }

    public static void updateDate(MongoCollection<Document> image, Document i, Configuration c) {
        Date d = getDateFromUrl(i.getString("url"), c);
        if (d == null) {
            return;
        } else {
            Document filter = new Document().append("_id", i.get("_id"));
            Document newDocument = new Document().append("md", d);
            Document query = new Document().append("$set", newDocument);
            image.updateOne(filter, query);
        }
    }

    /**
     * Only file attributes are analyzed, image is not loaded, no pixel information accounted.
     * @param image
     * @param imageObject
     */
    public static void processImageFile(
        MongoCollection<Document> image,
        Document imageObject,
        FileToolReport fileToolReport,
        PrintStream out,
        AtomicInteger totalImagesProcessed) {
        int n = totalImagesProcessed.incrementAndGet();
        if (n % 1000 == 0) {
            logger.info("Image processed for descriptors: {}", n);
        }
        try {
            String _id = ((ObjectId) imageObject.get("_id")).toString();
            String filename = ImageDownloader.imageFilename(_id, out);

            // Check if file exists
            File fd = new File(filename);
            if (!fd.exists()) {
                return;
            }

            // Gather file sha checksum
            String[] command = {"/usr/bin/sha512sum", filename};
            Runtime rt = Runtime.getRuntime();
            Process p = rt.exec(command);
            BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"));

            String line = br.readLine();
            StringTokenizer parser = new StringTokenizer(line, " ");
            String sha = parser.nextToken();

            // Gather image information
            if (!getImageDataFromFileTool(filename, fileToolReport)) {
                return;
            }

            // Assemble analysis results
            ImageFileAttributes a = ImageFileAttributes.builder()
                    .size(fd.length())
                    .shasum(sha)
                    .dx(fileToolReport.lastX)
                    .dy(fileToolReport.lastY)
                    .build();

            // Update info in database
            Document filter = new Document().append("_id", imageObject.get("_id"));
            Document newDocument = new Document().append("a", a);
            Document query = new Document("$set", newDocument);
            image.updateOne(filter, query);
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
