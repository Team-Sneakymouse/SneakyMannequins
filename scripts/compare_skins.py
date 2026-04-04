from PIL import Image
import sys

def get_opaque_pixels(img, region):
    # region: (x_start, y_start, width, height)
    x_s, y_s, w, h = region
    pixels = []
    for y in range(y_s, y_s + h):
        for x in range(x_s, x_s + w):
            p = img.getpixel((x, y))
            if p[3] > 0:
                pixels.append(((x, y), p[:3]))
    return pixels

def compare(img1_path, img2_path):
    img1 = Image.open(img1_path).convert("RGBA")
    img2 = Image.open(img2_path).convert("RGBA")

    regions = {
        "Handshake (0..3, 16..19)": (0, 16, 4, 4),
        "Choice Boxes (52..53, 16..19)": (52, 16, 2, 4),
        "Blink Rows (12..19, 16..19)": (12, 16, 8, 4),
        "Palette (60..63, 48..51)": (60, 48, 4, 4)
    }

    print(f"Comparing:")
    print(f"  1: {img1_path}")
    print(f"  2: {img2_path}\n")

    for name, region in regions.items():
        print(f"--- {name} ---")
        p1 = get_opaque_pixels(img1, region)
        p2 = get_opaque_pixels(img2, region)
        
        # Convert to dict for easy lookup
        d1 = {pos: color for pos, color in p1}
        d2 = {pos: color for pos, color in p2}
        
        all_pos = sorted(list(set(d1.keys()) | set(d2.keys())))
        
        if not all_pos:
            print("  (No opaque pixels in either image)")
            continue

        for pos in all_pos:
            c1 = d1.get(pos, "EMPTY")
            c2 = d2.get(pos, "EMPTY")
            match = "[MATCH]" if c1 == c2 else "[DIFF!!!]"
            print(f"  {pos}: 1={c1} | 2={c2} {match}")
        print()

if __name__ == "__main__":
    if len(sys.argv) < 3:
        # Default paths if none provided
        p1 = "/mnt/files/Workspaces/workspace-kotlin/HoloUX-Workspace/SneakyMannequins/web/images/finalized_oaKmAyfw.png"
        p2 = "/mnt/files/Workspaces/workspace-kotlin/HoloUX-Workspace/SneakyMannequins/web/images/31601b383ce3f1abcde1528709e1cc27035edf7489deb1798cafcc6a2de8df7b.png"
    else:
        p1 = sys.argv[1]
        p2 = sys.argv[2]
    
    compare(p1, p2)
