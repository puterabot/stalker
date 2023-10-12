package era.put;

import era.put.base.Util;
import java.io.File;
import java.io.InputStream;
import java.util.Properties;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MeDistributedCopierAndSyncTool {
    private static final Logger logger = LogManager.getLogger(MeDistributedCopierAndSyncTool.class);
    private static String REMOTE_SSH_INSTALL_PATH;
    private static int NUMBER_OF_DISTRIBUTED_AGENTS;
    private static String HOST_PATTERN;
    private static String USER_PATTERN;

    static {
        try {
            ClassLoader classLoader = Util.class.getClassLoader();
            InputStream input = classLoader.getResourceAsStream("application.properties");
            if (input == null) {
                throw new Exception("application.properties not found on classpath");
            }
            Properties properties = new Properties();
            properties.load(input);
            REMOTE_SSH_INSTALL_PATH = properties.getProperty("distributed.agents.relative.installation.path");
            NUMBER_OF_DISTRIBUTED_AGENTS = Integer.parseInt(properties.getProperty("distributed.agents.number.of.instances"));
            HOST_PATTERN = properties.getProperty("distributed.agents.host.pattern");
            USER_PATTERN = properties.getProperty("distributed.agents.user.pattern");
        } catch (Exception e) {
            logger.error("Variables not defined, check values at application.properties");
            System.exit(0);
        }
    }

    private static void runRemote(String command) throws Exception {
        logger.info(command);
        Process process = Runtime.getRuntime().exec(command);
        logger.info(process.exitValue());
    }

    private static void copyProjectToDistributedAgents() throws Exception {
        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            String sshConnection = getSshConnectionString(i);
            File projectFolder = new File("../../stalker");

            if (!projectFolder.exists() || !projectFolder.isDirectory()) {
                logger.error("Can not find project at " + projectFolder.getAbsolutePath());
                System.exit(9);
            }

            String projectPath = projectFolder.getAbsolutePath();

            String command;
            command = sshConnection + " mkdir -p " + REMOTE_SSH_INSTALL_PATH;
            runRemote(command);

            command = "tar -c \"projectPath\"";
        }
    }

    private static String getSshConnectionString(int i) {
        StringBuffer sshConnection = new StringBuffer("ssh ");
        int numberOfParameters = 0;
        if (USER_PATTERN.contains("%")) {
            numberOfParameters++;
        }
        if (HOST_PATTERN.contains("%")) {
            numberOfParameters++;
        }
        switch (numberOfParameters) {
            case 1:
                sshConnection.append(String.format(USER_PATTERN + "@" + HOST_PATTERN, i));
                break;
            case 2:
                sshConnection.append(String.format(USER_PATTERN + "@" + HOST_PATTERN, i, i));
                break;
            default:
                sshConnection.append(String.format(USER_PATTERN + "@" + HOST_PATTERN));
                break;
        }
        return sshConnection.toString();
    }

    public static void main(String[] args) {
        try {
            copyProjectToDistributedAgents();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
