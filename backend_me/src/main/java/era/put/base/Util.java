package era.put.base;

import era.put.MeLocalDataProcessorApp;
import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.util.Date;
import java.util.StringTokenizer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class Util {
    private static final Logger logger = LogManager.getLogger(Util.class);

    public static void printCurrentStackTrace() {
        StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stackTrace) {
            logger.info(element.getClassName() + "." + element.getMethodName()
                    + "(" + element.getFileName() + ":" + element.getLineNumber() + ")");
        }
    }

    public static void exitProgram(String msg) {
        logger.info("ABORTING PROGRAM. Reason: [" + msg + "]");
        printCurrentStackTrace();
        System.exit(666);
    }

    public static Integer extractIdFromPostUrl(String url) {
        StringTokenizer parser = new StringTokenizer(url, "/");
        String lastToken = null;
        while (parser.hasMoreTokens()) {
            lastToken = parser.nextToken();
        }
        try {
            if (lastToken == null) {
                return null;
            }
            return Integer.parseInt(lastToken);
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean filesAreDifferent(String a, String b) throws Exception {
        BufferedInputStream fileInputStreamA = new BufferedInputStream(new FileInputStream(a));
        BufferedInputStream fileInputStreamB = new BufferedInputStream(new FileInputStream(b));
        while (fileInputStreamA.available() > 0) {
            int da = fileInputStreamA.read();
            int db = fileInputStreamB.read();
            if (da != db) {
                fileInputStreamA.close();
                fileInputStreamB.close();
                return true;
            }
        }
        fileInputStreamA.close();
        fileInputStreamB.close();
        return false;
    }

    public static void reportDeltaTime(Date start, Date end) {
        long timeDifferenceMilliSeconds = end.getTime() - start.getTime();
        long minutes = timeDifferenceMilliSeconds / (60 * 1000);
        long seconds = (timeDifferenceMilliSeconds / 1000) % 60;
        logger.info("Elapsed time minutes:seconds -> {}: {}", minutes, seconds);
    }
}
