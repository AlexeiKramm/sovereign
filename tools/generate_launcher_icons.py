#!/usr/bin/env python3
"""
Generate Sovereign launcher icons (legacy raster fallbacks) across all standard mipmap densities.
Authoritative stencil: 3-Tier Dreadnought with 6.0px laser trench.
"""

import os
from PIL import Image, ImageDraw

DENSITIES = {
    'mipmap-mdpi': 48,
    'mipmap-hdpi': 72,
    'mipmap-xhdpi': 96,
    'mipmap-xxhdpi': 144,
    'mipmap-xxxhdpi': 192,
}

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES_DIR = os.path.join(BASE_DIR, 'app', 'src', 'main', 'res')

def render_icon(size: int, is_round: bool) -> Image.Image:
    scale = 4
    canvas_size = size * scale

    # Metallic stealth obsidian gradient background
    top_color = (28, 35, 46, 255)     # #1C232E
    bottom_color = (10, 13, 18, 255)  # #0A0D12

    base = Image.new('RGBA', (canvas_size, canvas_size), top_color)
    bottom = Image.new('RGBA', (canvas_size, canvas_size), bottom_color)
    mask_grad = Image.linear_gradient('L').resize((canvas_size, canvas_size))
    bg = Image.composite(bottom, base, mask_grad)

    # Shape mask
    shape_mask = Image.new('L', (canvas_size, canvas_size), 0)
    shape_draw = ImageDraw.Draw(shape_mask)
    if is_round:
        shape_draw.ellipse([0, 0, canvas_size - 1, canvas_size - 1], fill=255)
    else:
        radius = int(canvas_size * 0.22)
        shape_draw.rounded_rectangle([0, 0, canvas_size - 1, canvas_size - 1], radius=radius, fill=255)

    img = Image.new('RGBA', (canvas_size, canvas_size), (0, 0, 0, 0))
    img.paste(bg, (0, 0), shape_mask)
    draw = ImageDraw.Draw(img)

    # Subtle inner bevel / border
    if is_round:
        draw.ellipse([0, 0, canvas_size - 1, canvas_size - 1], outline=(255, 255, 255, 28), width=scale)
    else:
        radius = int(canvas_size * 0.22)
        draw.rounded_rectangle([0, 0, canvas_size - 1, canvas_size - 1], radius=radius, outline=(255, 255, 255, 28), width=scale)

    # Scaled 6.0px Dreadnought geometry (s = 0.60 of icon canvas)
    s = (canvas_size * 0.60) / 91.0
    cx = canvas_size / 2.0
    cy = canvas_size / 2.0

    def trans(pts):
        return [(cx + (x - 50.0) * s, cy + (y - 49.5) * s) for x, y in pts]

    tier1_l = trans([(47, 4), (47, 45), (32, 45)])
    tier1_r = trans([(53, 4), (68, 45), (53, 45)])
    tier2_l = trans([(47, 48), (30.5, 48), (26, 59), (47, 70)])
    tier2_r = trans([(53, 48), (53, 70), (74, 59), (69.5, 48)])
    tier3_l = trans([(47, 73), (28, 63), (47, 86)])
    tier3_r = trans([(53, 73), (53, 86), (72, 63)])
    diamond = trans([(47, 88.5), (50, 95), (53, 88.5), (50, 86)])

    white = (255, 255, 255, 255)
    for poly in [tier1_l, tier1_r, tier2_l, tier2_r, tier3_l, tier3_r, diamond]:
        draw.polygon(poly, fill=white)

    return img.resize((size, size), Image.Resampling.LANCZOS)

def main():
    print(f"Generating Sovereign icons into {RES_DIR}...")
    for folder, size in DENSITIES.items():
        target_dir = os.path.join(RES_DIR, folder)
        os.makedirs(target_dir, exist_ok=True)

        sq_path = os.path.join(target_dir, 'ic_launcher.webp')
        rd_path = os.path.join(target_dir, 'ic_launcher_round.webp')

        img_sq = render_icon(size, is_round=False)
        img_rd = render_icon(size, is_round=True)

        img_sq.save(sq_path, 'WEBP', quality=100, method=6)
        img_rd.save(rd_path, 'WEBP', quality=100, method=6)
        print(f"  {folder}: {size}x{size} -> ic_launcher.webp, ic_launcher_round.webp")

    print("Finished generating all raster launcher icons.")

if __name__ == '__main__':
    main()
