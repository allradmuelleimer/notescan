import sys, cv2, numpy as np
rng = np.random.default_rng(7)
page = cv2.imread(sys.argv[1]).astype(np.float32) / 255
H, W = 4000, 3000
# Untergrund: warmes dunkles Holz mit Maserung
y, x = np.mgrid[0:H, 0:W].astype(np.float32)
grain = cv2.GaussianBlur(rng.normal(0, 1, (H // 8, W // 8)).astype(np.float32), (0, 0), 6); grain = cv2.resize(grain, (W, H)) * 0.12 + rng.normal(0, .01, (H, W)).astype(np.float32)
V0 = int(sys.argv[3]) if len(sys.argv) > 3 else 0
bg = np.dstack([[0.30, 0.22, 0.42][V0] + grain, [0.33, 0.30, 0.40][V0] + grain, [0.36, 0.40, 0.37][V0] + grain])  # BGR
bg = cv2.GaussianBlur(bg, (0, 0), 2)
# Papier leicht getönt
paper = page * np.array([0.93, 0.96, 0.975], np.float32) + rng.normal(0, .01, page.shape).astype(np.float32)
ph, pw = paper.shape[:2]
src = np.float32([[0, 0], [pw, 0], [pw, ph], [0, ph]])
V = int(sys.argv[3]) if len(sys.argv) > 3 else 0
dst = [np.float32([[300, 270], [2770, 330], [2840, 3780], [190, 3700]]), np.float32([[260, 360], [2730, 280], [2820, 3700], [220, 3800]]), np.float32([[340, 300], [2800, 300], [2760, 3760], [280, 3740]])][V]
M = cv2.getPerspectiveTransform(src, dst)
warp = cv2.warpPerspective(paper, M, (W, H), flags=cv2.INTER_CUBIC)
mask = cv2.warpPerspective(np.ones((ph, pw), np.float32), M, (W, H))
# leichte Wölbung waagrecht
dy = (18 * np.sin(np.pi * (x / W))).astype(np.float32)
warp = cv2.remap(warp, x, y - dy, cv2.INTER_LINEAR)
mask = cv2.remap(mask, x, y - dy, cv2.INTER_LINEAR)
shadow = cv2.GaussianBlur(cv2.warpAffine(mask, np.float32([[1, 0, 25], [0, 1, 35]]), (W, H)), (0, 0), 30)
img = bg * (1 - 0.55 * shadow[..., None])
img = img * (1 - mask[..., None]) + warp * mask[..., None]
# Licht von oben links, Abfall nach unten rechts
light = 1.04 - 0.16 * ((x / W) * 0.6 + (y / H) * 0.8)
img = img * light[..., None]
img = cv2.GaussianBlur(img, (0, 0), 1.1)
img += rng.normal(0, .012, img.shape).astype(np.float32)
cv2.imwrite(sys.argv[2], np.clip(img * 255, 0, 255).astype(np.uint8), [cv2.IMWRITE_JPEG_QUALITY, 90])
