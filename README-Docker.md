# SneakyMannequins Web Server

This Docker Compose setup provides a simple nginx web server for hosting images that your SneakyMannequins plugin can reference.

## Quick Start

1. **Start the web server:**
   ```bash
   docker-compose up -d
   ```

2. **Stop the web server:**
   ```bash
   docker-compose down
   ```

3. **View logs:**
   ```bash
   docker-compose logs -f nginx
   ```

## Usage

- **Web server URL:** `http://localhost:8080`
- **Images directory:** `./web/images/`
- **Access images via:** `http://localhost:8080/images/your-image.png`

## External Exposure (for MineSkin API)

To use the [MineSkin API](https://mineskin.org/) while developing locally, your webserver must be reachable from the internet. The easiest "zero-install" way is using **Pinggy**.

1.  **Start Tunnel**:
    ```bash
    ssh -p 443 -R0:localhost:8080 a.pinggy.io
    ```
2.  **Update Config**: Update your `config.yml`'s `url-prefix` to the provided `.pinggy.link` URL.

## Troubleshooting

### Container not visible in Docker Desktop
If you ran `sudo docker-compose up`, the container might be running in the **system-wide engine** instead of the **Docker Desktop engine**.

1. **Stop & Remove**: `sudo docker-compose down`
2. **Ensure correct context**: `docker context use desktop-linux`
3. **Start without sudo**: `docker-compose up -d`

### Mounts Denied (Secondary Drives)
If your project is on a secondary mount (e.g., `/mnt/files/...`), Docker Desktop on Linux may deny volume mounts.

1. Open **Docker Desktop Settings** -> **Resources** -> **File Sharing**.
2. Add your mount path (e.g., `/mnt/files`) to the list.
3. Click **Apply & Restart**.

## Directory Structure

```
web/
├── images/          # Volume bind for your plugin's images
│   └── .gitkeep    # Ensures directory is tracked by git
└── nginx.conf      # nginx configuration
```

## Features

- **CORS enabled** for Minecraft clients & MineSkin API
- **Image caching** (1 hour)
- **Gzip compression**
- **Read-only volume bind** for Nginx serving
