"""Draws the WebSentry shield icon at the sizes Chrome asks for."""

from pathlib import Path

from PIL import Image, ImageDraw

# Brand blue taken from the popup design.
SHIELD = (76, 110, 245, 255)
TICK = (255, 255, 255, 255)

SIZES = [16, 32, 48, 128]
OUT_DIR = Path(__file__).resolve().parent


def shield_points(size: int):
    """Corner points of a simple shield shape, scaled to the icon size."""
    w = size
    pad = size * 0.14
    top = pad
    bottom = w - pad
    left = pad
    right = w - pad
    shoulder = top + (bottom - top) * 0.55
    return [
        (w / 2, top),
        (right, top + (bottom - top) * 0.12),
        (right, shoulder),
        (w / 2, bottom),
        (left, shoulder),
        (left, top + (bottom - top) * 0.12),
    ]


def draw_icon(size: int) -> Image.Image:
    # Drawn at 4x then shrunk, which gives smooth edges without anti-alias code.
    scale = 4
    big = size * scale
    image = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)

    draw.polygon([(x * scale, y * scale) for x, y in shield_points(size)], fill=SHIELD)

    # A tick mark in the middle of the shield.
    width = max(2, int(big * 0.09))
    draw.line(
        [
            (big * 0.34, big * 0.50),
            (big * 0.45, big * 0.62),
            (big * 0.68, big * 0.37),
        ],
        fill=TICK,
        width=width,
        joint="curve",
    )

    return image.resize((size, size), Image.LANCZOS)


def main() -> None:
    for size in SIZES:
        path = OUT_DIR / f"icon{size}.png"
        draw_icon(size).save(path)
        print(f"wrote {path.name}")


if __name__ == "__main__":
    main()
