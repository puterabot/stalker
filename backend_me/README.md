# ME web crawler agent

This java/gradle project contains several applications for feeding the database from the ME site.

### Dependencies

Note that current program has been developed and tested only on Linux Ubuntu 22.04. It is possible that
this could work on other systems as such MacOS or Windows, but additional research should be done on
how to support specific elements on that platforms, particularly an X11 server environment and the
availability of Chrome browser under that X11 environment.

For other than linux platforms, it is recommended to use a virtual machine with an Ubuntu 22.04 host.

For Linux Ubuntu 22.04, the following software is needed:
- **Java 17**: java, javac commands.
- **Berkeley Database utils**: db-dump command.
- **coreutils**: sha512sum command, useful to compute descriptors to identify repeated image files.
- **findimagedupes**: tool use to compute image descriptors, useful to detect global image visual similarities.
- **Xnest**: use for web scrapper agents to keep separate user sessions and avoid bot detectors.
- **Xvfb**: headless version of Xnest.
- **file**: tool to detect image types, useful to extract image size descriptors from jpeg files.

Recommended command to install needed dependencies:
```
sudo apt-get install db-util xnest xvfb coreutils findimagedupes
```

### Making a configuration file

Create an `./backend_me/src/main/application.properties` file and add the configuration lines with your database
credentials.

```
mongo.server=127.0.0.1
mongo.port=27017
mongo.user=theUser
mongo.password=thePwd
mongo.database=theDb
me.image.download.path=/some/folder/where/to/download/images
chromium.config.path=/some/folder/usually/user/.config/chromium
web.crawler.forever.and.beyond=true
web.crawler.post.listing.downloader.total.processes=1
web.crawler.post.listing.downloader.process.id=0
distributed.agents.relative.installation.path=some_folder_where_to_copy_project_to_distributed_systems_via_ssh
distributed.agents.number.of.instances=1
distributed.agents.host.pattern=network_names
distributed.agents.user.pattern=user_names
```

For web crawler distributed computing model, each step (i.e. `web.crawler.post.listing.downloader`) defines the
total number of agents to run (`.total.processes`), and an agent id from 0 to the number of agents minus one
(`.process.id`). For serial sequential run use values `1` and `0` respectively.

For host and user patterns, C/C++ style format strings can be used, as such `host_%02d`. The patterns will be
feed by a single integer value that will go from `0` to `distributed.agents.number.of.instances`.

Project can be imported on a IDE development tool as such Intellij.

### Starting up the scraper engine backend

To run:

```
./gradlew run -Papp=<mainClass>
```

where **mainClass** is one of the following:
- **MeCityListExtractorTool**: to be executed once per country, connects to ME site and browse regions and cities. This is useful to create or update configuration classes.
- **MeBotSeleniumApp**: default main class use, main web crawler to get post, profile and image information.
- **MeLocalDataProcessorApp**:  after having some downloaded posts, profiles and images, executes data cleanup, image processing and other analysis steps, as such face extraction and neural network trainning.
- **MeDistributedCopierAndSyncTool**: a set of procedures to help on copy, configure and sync system on a set of remote systems for distributed run
