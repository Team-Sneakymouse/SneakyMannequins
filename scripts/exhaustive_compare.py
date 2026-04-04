import sys
from PIL import Image

def is_in_uv(x, y):
    # Standard 64x64 Minecraft Skin UV layout (V1.8+)
    # Head: (0,0-63,15) 
    if 0 <= y < 16: return True
    
    # Body/Arms Row 1: (0,16-63,31)
    if 16 <= y < 32:
        # 0-3: Handshake (ETFs special area) -> JUNK
        if 0 <= x < 4: return False
        # 32-39: Unused (between Torso and Arms) -> JUNK
        if 32 <= x < 40: return False
        # 40-55: Right Arm Top/Sides
        # Note: 52-55 are often used for ETF Choice Boxes! Let's mark 52-63 as JUNK.
        if 52 <= x < 64: return False
        # 4-31: Legs/Torso -> UV
        if 4 <= x < 32: return True
        # 40-51: Right Arm Base/Sides -> UV
        if 40 <= x < 52: return True
        return False

    # Body/Arms Row 2: (0,32-47,47)
    if 32 <= y < 48:
        # 56-63: Unused -> JUNK
        if 56 <= x < 64: return False
        return True

    # Body/Arms Row 3: (0,48-63,63)
    if 48 <= y < 64:
        # 16-47: Left Leg/Arm -> UV
        # 0-15: Left Leg Overlay -> UV
        # 48-63: Left Arm Overlay -> UV
        # (Wait! Bottom right 60-63, 48-51 is a common palette area)
        return True
    
    return False

def exhaustive_compare(img1_path, img2_path):
    img1 = Image.open(img1_path).convert("RGBA")
    img2 = Image.open(img2_path).convert("RGBA")

    print(f"STRICT Exhaustive Comparison (TRUE JUNK):")
    print(f"  Ref: {img1_path}")
    print(f"  New: {img2_path}\n")

    diffs = []
    for y in range(64):
        for x in range(64):
            if is_in_uv(x, y): continue
            
            p1 = img1.getpixel((x, y))
            p2 = img2.getpixel((x, y))
            
            # If both are fully transparent, skip
            if p1[3] == 0 and p2[3] == 0: continue
            
            if p1 != p2:
                diffs.append(((x, y), p1, p2))

    if not diffs:
        print("No differences found in STRICT out-of-UV pixels!")
    else:
        print(f"Found {len(diffs)} differences in junk area:")
        last_y = -1
        for pos, c1, c2 in diffs:
            if pos[1] != last_y:
                print(f"  Row {pos[1]}:")
                last_y = pos[1]
            print(f"    Col {pos[0]}: Ref={c1} | New={c2}")

if __name__ == "__main__":
    if len(sys.argv) < 3:
        p1 = "/mnt/files/Workspaces/workspace-kotlin/HoloUX-Workspace/SneakyMannequins/web/images/31601b383ce3f1abcde1528709e1cc27035edf7489deb1798cafcc6a2de8df7b.png"
        p2 = "/mnt/files/Workspaces/workspace-kotlin/HoloUX-Workspace/SneakyMannequins/web/images/finalized_KyZpSGOg.png"
    else:
        p1 = sys.argv[1]
        p2 = sys.argv[2]
    exhaustive_compare(p1, p2)
