package era.put;

import era.put.base.Util;
import era.put.distributed.AsyncRemoteRunner;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    private static void runOsCommand(String command) throws Exception {
        logger.info(command);
        Process process = Runtime.getRuntime().exec(command);
        process.waitFor();

        InputStream standardOutputStream = process.getInputStream();
        BufferedReader br = new BufferedReader(new InputStreamReader(standardOutputStream));
        String line;
        while ((line = br.readLine()) != null) {
            logger.info(line);
        }

        InputStream standardErrorStream = process.getErrorStream();
        BufferedReader bre = new BufferedReader(new InputStreamReader(standardErrorStream));
        while ((line = bre.readLine()) != null) {
            logger.error(line);
        }

        int value = process.exitValue();
        if (value != 0) {
            logger.error("Status returned by command: {}", value);
            throw new RuntimeException("Can not execute [" + command + "] - review ssh permissions / authorized_keys on agent host");
        }
    }

    private static Thread runAsyncCommand(String command, int id) throws Exception {
        logger.info(command);
        AsyncRemoteRunner runner = new AsyncRemoteRunner(command, id);
        return new Thread(runner);
    }

    private static String getUserFolder(int i) {
        int numberOfParameters = 0;
        if (USER_PATTERN.contains("%")) {
            numberOfParameters++;
        }
        String userFolder;
        if (numberOfParameters == 1) {
            userFolder = String.format("/home/" + USER_PATTERN, i);
        } else {
            userFolder = String.format("/home/" + USER_PATTERN);
        }
        return userFolder;
    }

    private static String getSshConnectionString(int i) {
        StringBuilder sshConnection = new StringBuilder();
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

    private static void deleteTemporaryFile(String tarFilename) {
        File tarFile = new File(tarFilename);
        if (tarFile.exists()) {
            if (!tarFile.delete()) {
                throw new RuntimeException("Can not delete file " + tarFilename);
            }
        }
    }

    private static void copyProjectToDistributedAgents() throws Exception {
        String command;

        // 1. Prepare compressed file with project inside
        File projectFolder = new File("../../stalker");
        String projectPath = projectFolder.getAbsolutePath();
        if (!projectFolder.exists() || !projectFolder.isDirectory()) {
            logger.error("Can not find project at " + projectFolder.getAbsolutePath());
            System.exit(9);
        }

        String tarFilename = "/tmp/stalker_tmp.tar.bz2";
        deleteTemporaryFile(tarFilename);
        command = "tar cfj " + tarFilename + " -C " + projectPath + "/../ stalker";
        runOsCommand(command);

        // 2. Copy compressed file to all hosts
        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            String sshConnection = getSshConnectionString(i);
            logger.info("----- Copying project to host {}/{} -----", i, NUMBER_OF_DISTRIBUTED_AGENTS);
            command = "ssh " + sshConnection + " mkdir -p " + REMOTE_SSH_INSTALL_PATH;
            runOsCommand(command);
            command = "ssh " + sshConnection + " rm -rf " + REMOTE_SSH_INSTALL_PATH + "/stalker";
            runOsCommand(command);
            command = "scp " + tarFilename + " " + sshConnection + ":" + REMOTE_SSH_INSTALL_PATH;
            runOsCommand(command);
            command = "ssh " + sshConnection + " tar xfj $HOME/" + REMOTE_SSH_INSTALL_PATH + "/stalker_tmp.tar.bz2 -C $HOME/" + REMOTE_SSH_INSTALL_PATH;
            runOsCommand(command);
            command = "ssh " + sshConnection + " rm -f $HOME/" + REMOTE_SSH_INSTALL_PATH + "/stalker_tmp.tar.bz2";
            runOsCommand(command);
        }

        // 3. Clean up
        deleteTemporaryFile(tarFilename);
    }

    private static void syncApplicationPropertiesWithDistributedAgents() throws Exception {
        String propertiesFilename = "src/main/resources/application.properties";
        File localSource = new File(propertiesFilename);
        if (!localSource.exists()) {
            throw new RuntimeException("Can not open [" + localSource.getAbsolutePath() + "]");
        }

        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            logger.info("----- Copying configurations to host {}/{} -----", i, NUMBER_OF_DISTRIBUTED_AGENTS);
            String sshConnection = getSshConnectionString(i);
            String userFolder = getUserFolder(i);

            createCustomAgentPropertiesFile(propertiesFilename, userFolder, i);
            String command = "scp /tmp/application.properties " + sshConnection + ":" + REMOTE_SSH_INSTALL_PATH + "/stalker/backend_me/src/main/resources/application.properties";
            runOsCommand(command);
        }
        File toDelete = new File("/tmp/application.properties");
        if (!toDelete.delete()) {
            logger.error("Can not delete /tmp/application.properties file");
        }
    }

    private static void createCustomAgentPropertiesFile(String localSource, String userFolder, int i) throws IOException {
        BufferedWriter bw = new BufferedWriter(new FileWriter("/tmp/application.properties"));
        BufferedReader br = new BufferedReader(new FileReader(localSource));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("chromium.config.path")) {
                line = "chromium.config.path=" + userFolder + "/.config/chromium";
            }
            if (line.startsWith("distributed.agents")) {
                continue;
            }
            if (line.startsWith("web.crawler.post.listing.downloader.total.processes")) {
                line = "web.crawler.post.listing.downloader.total.processes=" + NUMBER_OF_DISTRIBUTED_AGENTS;
            }
            if (line.startsWith("web.crawler.post.listing.downloader.process.id")) {
                line = "web.crawler.post.listing.downloader.process.id=" + (i - 1);
            }

            bw.write(line + "\n");
        }
        bw.flush();
        br.close();
    }

    private static void startX11Sessions() throws Exception {
        Thread[] threads = new Thread[3 * NUMBER_OF_DISTRIBUTED_AGENTS];
        int t = 0;
        int dx = 0;
        int dy = 0;
        int incx = (3840 - 1920) / (NUMBER_OF_DISTRIBUTED_AGENTS - 1);
        int incy = (2160 - 1080) / (NUMBER_OF_DISTRIBUTED_AGENTS - 1);

        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            logger.info("----- Starting X11 server for host {}/{} -----", i, NUMBER_OF_DISTRIBUTED_AGENTS);
            String sshConnection = getSshConnectionString(i);
            String command = "ssh -Y " + sshConnection + " Xnest :" + (100 + i) + " -geometry 1920x1080+" + dx + "+" + dy + " -name " + sshConnection;
            threads[t] = runAsyncCommand(command, i);
            threads[t].start();
            t++;
            dx += incx;
            dy += incy;
        }

        Thread.sleep(2000);

        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            logger.info("----- Starting mwm for host {}/{} -----", i, NUMBER_OF_DISTRIBUTED_AGENTS);
            String sshConnection = getSshConnectionString(i);
            String command = "ssh " + sshConnection + " mwm -display :" + (100 + i);
            threads[t] = runAsyncCommand(command, i);
            threads[t].start();
            t++;
        }

        for (int i = 1; i <= NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            logger.info("----- Starting mwm for host {}/{} -----", i, NUMBER_OF_DISTRIBUTED_AGENTS);
            String sshConnection = getSshConnectionString(i);
            String projectFolder = getUserFolder(i) + "/usr/paradigmas/stalker/backend_me";
            String command = "ssh " + sshConnection + " uxterm -ls -sb -fn 10x20 -display :" + (100 + i) + " -e " + projectFolder + "/gradlew -p " + projectFolder + " run";
            //String command = "ssh " + sshConnection + " uxterm -ls -sb -fn 10x20 -display :" + (100 + i) + " -e chrome";
            threads[t] = runAsyncCommand(command, i);
            threads[t].start();
            t++;
        }

        for (int i = 0; i < 3 * NUMBER_OF_DISTRIBUTED_AGENTS; i++) {
            threads[i].join();
        }
    }
    public static void main(String[] args) {
        try {
            copyProjectToDistributedAgents();
            syncApplicationPropertiesWithDistributedAgents();
            startX11Sessions();
        } catch (Exception e) {
            logger.error(e);
        }
    }
}
