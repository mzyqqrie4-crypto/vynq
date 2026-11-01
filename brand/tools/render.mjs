// Rasterises brand/svg masters into platform PNG exports using resvg.
// Run: cd brand/tools && npm install && node render.mjs
import { Resvg } from "@resvg/resvg-js";
import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const brand = join(dirname(fileURLToPath(import.meta.url)), "..");
const svg = (name) => readFileSync(join(brand, "svg", name), "utf8");

function render(name, width, out) {
  const png = new Resvg(svg(name), { fitTo: { mode: "width", value: width } }).render().asPng();
  const file = join(brand, "exports", out);
  mkdirSync(dirname(file), { recursive: true });
  writeFileSync(file, png);
  console.log(`${out}  ${width}px`);
}

// iOS: one 1024 master per appearance (Xcode 15+ single-size asset catalog).
render("app-icon-dark.svg", 1024, "ios/AppIcon-Dark-1024.png");
render("app-icon-light.svg", 1024, "ios/AppIcon-Light-1024.png");
render("app-icon-mono.svg", 1024, "ios/AppIcon-Tinted-1024.png");
// Play Store listing icon.
render("app-icon-dark.svg", 512, "android/play-store-icon-512.png");
// Presentation / social.
render("vynq-symbol.svg", 1024, "presentation/vynq-symbol-1024.png");
render("vynq-symbol.svg", 64, "presentation/vynq-symbol-64.png");
render("vynq-logo-stacked-dark.svg", 1600, "presentation/vynq-logo-stacked-dark.png");
render("vynq-logo-horizontal-dark.svg", 1600, "presentation/vynq-logo-horizontal-dark.png");
render("vynq-logo-horizontal-light.svg", 1600, "presentation/vynq-logo-horizontal-light.png");
render("splash-dark.svg", 1290, "presentation/splash-dark.png");
