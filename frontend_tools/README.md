# UI for verifying ME groups, profiles, posts and images data.

## Instalation

### Install NodeJS

### Install MeteorJS

```
curl https://install.meteor.com/ | sh
```

### Install an static image web server

The downloaded images from the `backend_me` service must be available for this frontend to use.
It is recommended to install an NGINX web server, using the following configuration:

```
server {
    listen 80;
    server_name yourServerName;

    location /me {
        alias /path/to/downloaded/images/main/folder;
    }
    error_page 404 /404.html;
}
```

when started, take note of the url (on this example `http://yourServerName/me`), it is needed to
include in the configuration.

### Configuration

Create a one line text file called `./config/dburl.conf` for the database configuration:
```
mongodb://yourUser:yourPaswd@dbserverhost:27017/mileroticos?authSource=admin
```

Create file `config/appConfig.json`:
```
{
    "imageBaseUrl": "http://yourStaticImageServer/imagesPath"
}
```

### Project setup

For the first time, before running the project, install the local meteor project dependencies:
```
./scripts/bootstrap.sh
```

### Project launch

Each time the project is started, the backend init command is:
```
./scripts/runLocal.sh
```
this will make the frontend available via web. By default, it is started on
`http://localhost:3001`
check the `./scripts/runLocal.sh` file for changing the port.

For publishing, it is recommended to use a proxy configuration on NGINX.
