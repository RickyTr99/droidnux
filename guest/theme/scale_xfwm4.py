"""Scales an xfwm4 theme (PNGs and themerc pixel values): scale_xfwm4.py <src> <dst> <factor>."""
import os
import re
import sys

from PIL import Image

src, dst, factor = sys.argv[1], sys.argv[2], float(sys.argv[3])
os.makedirs(dst, exist_ok=True)
for name in os.listdir(src):
    path = os.path.join(src, name)
    if name.endswith('.png'):
        image = Image.open(path).convert('RGBA')
        size = (max(1, round(image.width * factor)), max(1, round(image.height * factor)))
        image.resize(size, Image.LANCZOS).save(os.path.join(dst, name))
    elif name == 'themerc':
        text = open(path).read()
        for key in ('button_offset', 'button_spacing', 'title_horizontal_offset', 'frame_border_top'):
            text = re.sub(rf'^{key}=(\d+)', lambda m: f'{key}={round(int(m.group(1)) * factor)}', text, flags=re.M)
        open(os.path.join(dst, name), 'w', newline='\n').write(text)
print('scaled', len(os.listdir(dst)), 'files')
