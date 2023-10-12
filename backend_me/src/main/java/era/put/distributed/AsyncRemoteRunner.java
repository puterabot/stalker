package era.put.distributed;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class AsyncRemoteRunner implements Runnable {
    private static final Logger logger = LogManager.getLogger(AsyncRemoteRunner.class);
    private String command;
    private int id;
    public AsyncRemoteRunner(String command, int id) {
        this.command = command;
        this.id = id;
    }
    @Override
    public void run() {
        try {
            if (command.contains("chrome") || command.contains("gradlew")) {
                Thread.sleep(id * 3000);
            }
            Process process = Runtime.getRuntime().exec(command);
            process.waitFor();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
