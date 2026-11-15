"""Draws the Clean Your TV Master logo (red planet, play sign, tilted ring) into icon and banner PNGs."""
import math, random, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

OUT = sys.argv[1]
SS = 4  # supersampling


def lerp(a, b, t):
    return tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(len(a)))


def space(w, h, rounded=0):
    img = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    bg = Image.new("RGBA", (w, h))
    px = bg.load()
    cx, cy = w * 0.3, h * 0.25
    md = math.hypot(w, h)
    for y in range(h):
        for x in range(w):
            d = math.hypot(x - cx, y - cy) / md
            t = min(1, d * 1.4)
            px[x, y] = lerp((92, 4, 18, 255), (6, 2, 4, 255), t)
    rnd = random.Random(7)
    d = ImageDraw.Draw(bg)
    for _ in range(int(w * h / 9000 / SS)):
        x, y = rnd.random() * w, rnd.random() * h
        r = (0.6 + rnd.random() ** 3 * 2.2) * SS * (w / (512 * SS)) ** 0.5
        a = int(120 + rnd.random() * 135)
        col = rnd.choice([(255, 255, 255), (255, 255, 255), (255, 214, 208), (255, 233, 201)])
        d.ellipse([x - r, y - r, x + r, y + r], fill=col + (a,))
    if rounded:
        mask = Image.new("L", (w, h), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], rounded, fill=255)
        img.paste(bg, (0, 0), mask)
        return img
    return bg


RING = [(255, 30, 45), (138, 0, 18), (255, 90, 54), (255, 210, 194), (255, 30, 45)]


def ring_color(t):
    t = t % 1 * (len(RING) - 1)
    i = int(t)
    return lerp(RING[i], RING[i + 1], t - i)


def draw_logo(img, cx, cy, R):
    w, h = img.size
    # glow
    glow = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(glow).ellipse([cx - R * 1.5, cy - R * 1.5, cx + R * 1.5, cy + R * 1.5], fill=(255, 45, 70, 110))
    glow = glow.filter(ImageFilter.GaussianBlur(R * 0.45))
    img.alpha_composite(glow)
    rx, ry, tilt = R * 1.8, R * 0.5, math.radians(-22)
    width = max(2, R * 0.13)

    def ring(back):
        layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
        d = ImageDraw.Draw(layer)
        n = 240
        for i in range(n):
            a0, a1 = math.pi * 2 * i / n, math.pi * 2 * (i + 1.4) / n
            behind = math.sin((a0 + a1) / 2) < 0
            if behind != back:
                continue
            pts = []
            for a in (a0, a1):
                x, y = math.cos(a) * rx, math.sin(a) * ry
                pts.append((cx + x * math.cos(tilt) - y * math.sin(tilt), cy + x * math.sin(tilt) + y * math.cos(tilt)))
            col = ring_color(i / n)
            d.line(pts, fill=col + ((160 if back else 255),), width=int(width))
        img.alpha_composite(layer)

    ring(True)
    # planet with light from the top left
    planet = Image.new("RGBA", img.size, (0, 0, 0, 0))
    pp = planet.load()
    lx, ly = cx - R * 0.35, cy - R * 0.4
    stops = [(0, (255, 106, 90)), (0.3, (224, 20, 44)), (0.75, (122, 0, 18)), (1, (42, 0, 6))]
    for y in range(int(cy - R) - 1, int(cy + R) + 2):
        for x in range(int(cx - R) - 1, int(cx + R) + 2):
            dd = math.hypot(x - cx, y - cy)
            if dd > R:
                continue
            t = min(1, math.hypot(x - lx, y - ly) / (R * 1.5))
            for j in range(len(stops) - 1):
                if t <= stops[j + 1][0]:
                    u = (t - stops[j][0]) / (stops[j + 1][0] - stops[j][0])
                    col = lerp(stops[j][1], stops[j + 1][1], u)
                    break
            edge = max(0, min(1, (R - dd) / 1.5))
            pp[x, y] = col + (int(255 * edge),)
    img.alpha_composite(planet)
    # play sign
    ps = R * 0.46
    d = ImageDraw.Draw(img)
    d.polygon([(cx - ps * 0.5, cy - ps * 0.78), (cx + ps * 0.88, cy), (cx - ps * 0.5, cy + ps * 0.78)], fill=(255, 255, 255, 255))
    ring(False)
    # moon on the ring
    a = math.radians(35)
    x, y = math.cos(a) * rx, math.sin(a) * ry
    mx, my = cx + x * math.cos(tilt) - y * math.sin(tilt), cy + x * math.sin(tilt) + y * math.cos(tilt)
    mr = R * 0.12
    d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=(255, 233, 226, 255))


def save(img, size, path):
    img.resize(size, Image.LANCZOS).save(path)


# Icon
S = 512 * SS
icon = space(S, S, rounded=int(S * 0.2))
draw_logo(icon, S / 2, S * 0.5, S * 0.24)
save(icon, (512, 512), OUT + "/icon-512.png")
save(icon, (192, 192), OUT + "/icon.png")

# Banner (640x360)
W, H = 640 * SS, 360 * SS
banner = space(W, H)
draw_logo(banner, W * 0.21, H * 0.5, H * 0.19)
d = ImageDraw.Draw(banner)
f1 = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 46 * SS)
f2 = ImageFont.truetype("C:/Windows/Fonts/segoeuib.ttf", 64 * SS)
f3 = ImageFont.truetype("C:/Windows/Fonts/segoeui.ttf", 22 * SS)
x = W * 0.47
d.text((x, H * 0.27), "Clean Your TV", font=f1, fill=(255, 255, 255, 255))
d.text((x, H * 0.40), "MASTER", font=f2, fill=(255, 64, 78, 255))
d.text((x + 3 * SS, H * 0.66), "por Jorge Master", font=f3, fill=(255, 220, 215, 220))
save(banner, (640, 360), OUT + "/banner.png")
print("ok")
