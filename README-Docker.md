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

## Directory Structure

```
web/
├── images/          # Volume bind for your plugin's images
│   └── .gitkeep    # Ensures directory is tracked by git
└── nginx.conf      # nginx configuration
```

## Features

- **CORS enabled** for Minecraft clients
- **Image caching** (1 hour)
- **Gzip compression** for better performance
- **Read-only volume bind** for security

## For Your Plugin

Your SneakyMannequins plugin can:
1. Save skin images to `./web/images/`
2. Reference them via URLs like `http://localhost:8080/images/skin-123.png`
3. Use these URLs in the texture JSON for mannequins

## Example

If you save a skin image as `./web/images/player-skin.png`, it will be accessible at:
`http://localhost:8080/images/player-skin.png`
