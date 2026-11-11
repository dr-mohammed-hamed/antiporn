import os
import sys

try:
    sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass
import numpy as np
from PIL import Image, ImageFilter, ImageDraw
from collections import deque

from pathlib import Path

def generate_assets():
    project_root = Path(__file__).resolve().parent.parent
    source_path = str(project_root / "art" / "hisn_brand_logo.jpg")
    res_dir = str(project_root / "app" / "src" / "main" / "res")

    if not os.path.exists(source_path):
        print(f"Error: Source image not found at {source_path}")
        sys.exit(1)

    print(f"Loading source image from {source_path}...")
    img = Image.open(source_path).convert("RGB")
    w, h = img.size
    arr = np.array(img).astype(np.int32)
    bg_color = np.array([9, 26, 52])

    print("Segmenting outer background via flood-fill...")
    diff = np.sqrt(np.sum((arr - bg_color)**2, axis=2))
    is_bg_candidate = (diff < 22)

    visited = np.zeros((h, w), dtype=bool)
    queue = deque()

    for x in range(w):
        if is_bg_candidate[0, x] and not visited[0, x]:
            visited[0, x] = True
            queue.append((x, 0))
        if is_bg_candidate[h-1, x] and not visited[h-1, x]:
            visited[h-1, x] = True
            queue.append((x, h-1))
    for y in range(h):
        if is_bg_candidate[y, 0] and not visited[y, 0]:
            visited[y, 0] = True
            queue.append((0, y))
        if is_bg_candidate[y, w-1] and not visited[y, w-1]:
            visited[y, w-1] = True
            queue.append((w-1, y))

    while queue:
        cx, cy = queue.popleft()
        for nx, ny in [(cx+1, cy), (cx-1, cy), (cx, cy+1), (cx, cy-1)]:
            if 0 <= nx < w and 0 <= ny < h:
                if not visited[ny, nx] and is_bg_candidate[ny, nx]:
                    visited[ny, nx] = True
                    queue.append((nx, ny))

    # Outer background is True in visited
    # Alpha mask: 0 where visited is True, 255 elsewhere
    alpha_mask = np.where(visited, 0, 255).astype(np.uint8)

    # Soften outer boundary by 1.5px blur for beautiful antialiased edges
    mask_img = Image.fromarray(alpha_mask, mode="L").filter(ImageFilter.GaussianBlur(radius=1.5))

    # Create full 2048x2048 RGBA image of the transparent emblem
    emblem_rgba = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    emblem_rgba.paste(img, (0, 0), mask=mask_img)

    # Find tight bounding box of emblem
    bbox = emblem_rgba.getbbox()
    print(f"Emblem tight bounding box: {bbox}")
    emblem_cropped = emblem_rgba.crop(bbox)

    # 1. Generate In-App Header Emblem (ic_ismah_emblem.png)
    # Bounding box cropped with balanced 10% padding
    cw, ch = emblem_cropped.size
    pad_y = int(ch * 0.08)
    pad_x = int(cw * 0.08)
    inapp_canvas = Image.new("RGBA", (cw + pad_x*2, ch + pad_y*2), (0, 0, 0, 0))
    inapp_canvas.paste(emblem_cropped, (pad_x, pad_y), emblem_cropped)
    # Resize to high-res 800x800 for crisp rendering inside glow badge
    inapp_final = inapp_canvas.resize((800, int(800 * (ch + pad_y*2) / (cw + pad_x*2))), Image.Resampling.LANCZOS)
    inapp_path = os.path.join(res_dir, "drawable", "ic_ismah_emblem.png")
    inapp_final.save(inapp_path, format="PNG")
    print(f"Saved: {inapp_path} ({inapp_final.size})")

    # Also save ic_ismah_logo.png as high-res 1024x1024
    logo_path = os.path.join(res_dir, "drawable", "ic_ismah_logo.png")
    img.resize((1024, 1024), Image.Resampling.LANCZOS).save(logo_path, format="PNG")
    print(f"Saved: {logo_path} (1024x1024)")

    # 2. Generate Adaptive Foregrounds (drawable-*/ic_launcher_foreground.png)
    # Canvas: 108dp. Safe zone: 72dp (66.6% diameter).
    # To match standard system apps (Google Maps, Files) with ~25% optical breathing margin:
    # Shield height should be 72% of the 72dp safe zone = ~52dp out of 108dp canvas (ratio ~0.48)
    density_foregrounds = {
        "drawable-mdpi": 108,
        "drawable-hdpi": 162,
        "drawable-xhdpi": 216,
        "drawable-xxhdpi": 324,
        "drawable-xxxhdpi": 432,
        "drawable": 432
    }

    for folder, size in density_foregrounds.items():
        out_dir = os.path.join(res_dir, folder)
        os.makedirs(out_dir, exist_ok=True)
        target_h = int(size * 0.48)
        target_w = int(cw * (target_h / ch))
        scaled_emblem = emblem_cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)

        canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        offset_x = (size - target_w) // 2
        offset_y = (size - target_h) // 2
        canvas.paste(scaled_emblem, (offset_x, offset_y), scaled_emblem)

        fg_path = os.path.join(out_dir, "ic_launcher_foreground.png")
        canvas.save(fg_path, format="PNG")
        print(f"Saved: {fg_path} ({size}x{size})")

        # 3. Generate Monochrome Themed Icons (drawable-*/ic_launcher_monochrome.png)
        # Android 13+ Material You: pure white silhouette with alpha
        alpha_channel = canvas.getchannel("A")
        mono_img = Image.new("RGBA", (size, size), (255, 255, 255, 0))
        mono_img.putalpha(alpha_channel)
        mono_path = os.path.join(out_dir, "ic_launcher_monochrome.png")
        mono_img.save(mono_path, format="PNG")
        print(f"Saved: {mono_path} ({size}x{size})")

    # 4. Generate Legacy Mipmaps (mipmap-*/ic_launcher.webp & ic_launcher_round.webp)
    density_mipmaps = {
        "mipmap-mdpi": 48,
        "mipmap-hdpi": 72,
        "mipmap-xhdpi": 96,
        "mipmap-xxhdpi": 144,
        "mipmap-xxxhdpi": 192
    }

    bg_rgb = (9, 26, 52)

    for folder, size in density_mipmaps.items():
        out_dir = os.path.join(res_dir, folder)
        os.makedirs(out_dir, exist_ok=True)

        # Scale emblem for legacy launcher (68% of canvas height for balanced margins)
        target_h = int(size * 0.68)
        target_w = int(cw * (target_h / ch))
        scaled_emblem = emblem_cropped.resize((target_w, target_h), Image.Resampling.LANCZOS)
        offset_x = (size - target_w) // 2
        offset_y = (size - target_h) // 2

        # Square Icon with subtle rounded corners (4px on mdpi, 16px on xxxhdpi)
        square_icon = Image.new("RGBA", (size, size), (*bg_rgb, 255))
        square_icon.paste(scaled_emblem, (offset_x, offset_y), scaled_emblem)

        # Apply rounded corner mask to square legacy icon
        corner_radius = max(2, int(size * 0.16))
        round_rect_mask = Image.new("L", (size * 4, size * 4), 0)
        draw = ImageDraw.Draw(round_rect_mask)
        draw.rounded_rectangle([(0, 0), (size * 4 - 1, size * 4 - 1)], radius=corner_radius * 4, fill=255)
        round_rect_mask = round_rect_mask.resize((size, size), Image.Resampling.LANCZOS)

        final_square = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        final_square.paste(square_icon, (0, 0), mask=round_rect_mask)
        square_path = os.path.join(out_dir, "ic_launcher.webp")
        final_square.save(square_path, format="WEBP", quality=95)
        print(f"Saved: {square_path} ({size}x{size})")

        # Round Icon with circle mask
        circle_mask = Image.new("L", (size * 4, size * 4), 0)
        draw_c = ImageDraw.Draw(circle_mask)
        draw_c.ellipse([(0, 0), (size * 4 - 1, size * 4 - 1)], fill=255)
        circle_mask = circle_mask.resize((size, size), Image.Resampling.LANCZOS)

        final_round = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        final_round.paste(square_icon, (0, 0), mask=circle_mask)
        round_path = os.path.join(out_dir, "ic_launcher_round.webp")
        final_round.save(round_path, format="WEBP", quality=95)
        print(f"Saved: {round_path} ({size}x{size})")

    print("\nAll Android icon assets generated successfully!")

if __name__ == "__main__":
    generate_assets()
