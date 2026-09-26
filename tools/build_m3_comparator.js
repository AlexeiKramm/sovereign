const fs = require('fs');
const path = require('path');

// 1. Read the material-color-utilities bundle
const mcuPath = path.join(__dirname, '../docs/work/visual-design/material-color-utilities.js');
let mcuCode = fs.readFileSync(mcuPath, 'utf8');

// 2. Convert export to globalThis / window
const exportRegex = /export\s*\{([^}]+)\};/;
const m = mcuCode.match(exportRegex);
if (!m) {
  console.error("Could not find export statement in MCU code!");
  process.exit(1);
}

const pairs = m[1].split(',').map(s => s.trim()).map(p => {
  const parts = p.split(/\s+as\s+/);
  if (parts.length === 2) return `${parts[1]}: ${parts[0]}`;
  return `${p}: ${p}`;
}).join(',\n    ');

const mcuGlobalBundle = mcuCode.replace(exportRegex, `
globalThis.MaterialColorUtils = {
    ${pairs}
};
if (typeof window !== 'undefined') {
    window.MaterialColorUtils = globalThis.MaterialColorUtils;
}
`);

// 3. Save as standalone global JS file as well
fs.writeFileSync(path.join(__dirname, '../docs/work/visual-design/material-color-utilities.global.js'), mcuGlobalBundle);

// 4. Comparator HTML template with non-modal mobile color sliders + wheel/spectrum + presets + card border toggle
const htmlContent = `<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>Material Design 3 vs Current Hand-Tuned Tokens — Visual Comparator</title>
<style>
  :root {
    --bg-page: #0D1117;
    --card-surface: #161B22;
    --border-ui: #30363D;
    --text-primary: #F0F6FC;
    --text-secondary: #8B949E;
    --text-tertiary: #6E7681;
    --accent: #FF9248;
    --font-stack: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif;
  }

  * { box-sizing: border-box; margin: 0; padding: 0; }
  body {
    background: var(--bg-page);
    color: var(--text-primary);
    font-family: var(--font-stack);
    line-height: 1.5;
    padding-bottom: 80px;
    -webkit-font-smoothing: antialiased;
  }

  header {
    background: #11151C;
    border-bottom: 1px solid var(--border-ui);
    padding: 16px 20px;
    position: sticky;
    top: 0;
    z-index: 100;
    box-shadow: 0 4px 20px rgba(0,0,0,0.4);
  }

  .header-inner {
    max-width: 1440px;
    margin: 0 auto;
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    justify-content: space-between;
    gap: 14px;
  }

  .title-group h1 {
    font-size: 19px;
    font-weight: 700;
    letter-spacing: -0.02em;
    display: flex;
    align-items: center;
    gap: 10px;
  }
  .title-group p {
    font-size: 12px;
    color: var(--text-secondary);
    margin-top: 2px;
  }

  .controls-panel {
    background: #181E26;
    padding: 12px 16px;
    border-radius: 12px;
    border: 1px solid var(--border-ui);
    display: flex;
    flex-wrap: wrap;
    align-items: center;
    gap: 18px;
  }

  .control-group {
    display: flex;
    align-items: center;
    gap: 10px;
  }

  .control-group label {
    font-weight: 600;
    color: var(--text-secondary);
    font-size: 11px;
    text-transform: uppercase;
    letter-spacing: 0.05em;
    white-space: nowrap;
  }

  /* Sliders */
  .slider-item {
    display: flex;
    flex-direction: column;
    gap: 2px;
  }
  .slider-header {
    display: flex;
    justify-content: space-between;
    font-size: 10px;
    font-weight: 600;
    color: var(--text-secondary);
  }

  input[type="range"] {
    -webkit-appearance: none;
    appearance: none;
    height: 10px;
    border-radius: 5px;
    outline: none;
    background: #2D333B;
    cursor: pointer;
  }
  input[type="range"]::-webkit-slider-thumb {
    -webkit-appearance: none;
    appearance: none;
    width: 18px;
    height: 18px;
    border-radius: 50%;
    background: #FFFFFF;
    border: 2px solid #000;
    cursor: pointer;
    box-shadow: 0 1px 4px rgba(0,0,0,0.5);
  }

  #hueSlider {
    background: linear-gradient(to right, 
      #ff0000 0%, #ffff00 17%, #00ff00 33%, #00ffff 50%, #0000ff 67%, #ff00ff 83%, #ff0000 100%);
    width: 130px;
  }
  #chromaSlider {
    width: 90px;
  }
  #toneSlider {
    width: 90px;
  }

  .seed-display-chip {
    display: flex;
    align-items: center;
    gap: 8px;
    background: #0D1117;
    border: 1px solid var(--border-ui);
    padding: 4px 8px;
    border-radius: 8px;
  }
  .seed-color-box {
    width: 24px;
    height: 24px;
    border-radius: 6px;
    border: 1px solid rgba(255,255,255,0.2);
  }
  .hex-input {
    background: transparent;
    border: none;
    color: var(--text-primary);
    font-family: monospace;
    font-size: 13px;
    width: 76px;
    text-transform: uppercase;
    font-weight: 700;
    outline: none;
  }

  select {
    background: #0D1117;
    border: 1px solid var(--border-ui);
    color: var(--text-primary);
    padding: 6px 10px;
    border-radius: 6px;
    font-size: 12px;
    cursor: pointer;
  }

  .preset-row {
    max-width: 1440px;
    margin: 10px auto 0;
    display: flex;
    align-items: center;
    gap: 6px;
    flex-wrap: wrap;
  }
  .preset-chip {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: #1C2128;
    border: 1px solid var(--border-ui);
    padding: 4px 10px;
    border-radius: 14px;
    font-size: 11px;
    font-weight: 500;
    cursor: pointer;
    transition: all 0.15s ease;
    color: var(--text-primary);
  }
  .preset-chip:hover {
    border-color: #58A6FF;
    transform: translateY(-1px);
  }
  .preset-dot {
    width: 10px;
    height: 10px;
    border-radius: 50%;
  }

  .content-container {
    max-width: 1440px;
    margin: 20px auto;
    padding: 0 16px;
  }

  .callout-box {
    background: rgba(88, 166, 255, 0.08);
    border-left: 3px solid #58A6FF;
    padding: 12px 16px;
    border-radius: 0 8px 8px 0;
    font-size: 13px;
    color: #C9D1D9;
    margin-bottom: 20px;
    line-height: 1.6;
  }
  .callout-box b {
    color: #58A6FF;
  }

  .comparison-grid {
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 24px;
  }

  @media (max-width: 1080px) {
    .comparison-grid {
      grid-template-columns: 1fr;
    }
  }

  .column-card {
    background: var(--card-surface);
    border: 1px solid var(--border-ui);
    border-radius: 16px;
    overflow: hidden;
    display: flex;
    flex-direction: column;
  }

  .column-header {
    padding: 16px 20px;
    border-bottom: 1px solid var(--border-ui);
    background: rgba(255,255,255,0.02);
  }

  .column-header h2 {
    font-size: 16px;
    font-weight: 700;
    display: flex;
    align-items: center;
    justify-content: space-between;
  }

  .badge-tag {
    font-size: 11px;
    font-weight: 600;
    text-transform: uppercase;
    padding: 3px 8px;
    border-radius: 6px;
    letter-spacing: 0.05em;
  }
  .tag-locked { background: #238636; color: #fff; }
  .tag-dynamic { background: #1F6FEB; color: #fff; }

  .column-header p {
    font-size: 12px;
    color: var(--text-secondary);
    margin-top: 3px;
  }

  .theme-subhead {
    font-size: 11px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    color: var(--text-secondary);
    margin: 16px 0 10px 0;
    display: flex;
    align-items: center;
    gap: 8px;
  }
  .theme-subhead::after {
    content: '';
    flex: 1;
    height: 1px;
    background: var(--border-ui);
  }

  .stage-section {
    padding: 16px 20px 24px;
  }

  .preview-stage {
    padding: 20px;
    border-radius: 14px;
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 20px;
    position: relative;
    border: 1px solid rgba(128,128,128,0.15);
    box-shadow: inset 0 2px 8px rgba(0,0,0,0.2);
  }

  .preview-stage.dark-stage {
    background: #0B0E14;
  }
  .preview-stage.light-stage {
    background: #F4F4F6;
  }

  .pill-row-wrap {
    display: flex;
    align-items: center;
    gap: 32px;
    justify-content: center;
    width: 100%;
  }

  .component-block {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: 6px;
  }
  .component-label {
    font-size: 10px;
    font-weight: 700;
    color: var(--text-tertiary);
    text-transform: uppercase;
    letter-spacing: 0.05em;
  }

  /* Minimized Pill CSS: 40dp x 60dp, 15dp radius, 2dp border (exact app match) */
  .min-pill {
    width: 40px;
    height: 60px;
    border-radius: 15px;
    border-width: 2px;
    border-style: solid;
    display: flex;
    align-items: center;
    justify-content: center;
    position: relative;
    box-shadow: 0 4px 12px rgba(0,0,0,0.25);
    transition: background 0.15s ease, border-color 0.15s ease;
  }

  /* Expanded Card CSS: 340dp x 210dp, 16dp pad, 24dp radius (exact app match, no border by default) */
  .card-box {
    width: 340px;
    height: 210px;
    border-radius: 24px;
    padding: 16px;
    display: flex;
    flex-direction: column;
    position: relative;
    box-shadow: 0 8px 30px rgba(0,0,0,0.35);
    transition: background 0.15s ease, border-color 0.15s ease;
    border: none;
  }

  .card-header-row {
    height: 20px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex: none;
  }
  .card-status-label {
    display: flex;
    align-items: center;
    gap: 7px;
    font-size: 15px;
    font-weight: 600;
    line-height: 20px;
  }
  .status-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    flex: none;
  }

  /* Exactly 100dp transcript box with 5 lines at 16sp / 19sp */
  .card-transcript-box {
    height: 100px;
    font-size: 16px;
    line-height: 19px;
    font-weight: 500;
    overflow: hidden;
    display: -webkit-box;
    -webkit-line-clamp: 5;
    -webkit-box-orient: vertical;
    word-break: break-word;
    flex: none;
  }

  /* 36dp action row height with 41dp circular buttons */
  .card-action-row {
    height: 36px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex: none;
    position: relative;
  }

  .act-btn {
    width: 41px;
    height: 41px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex: none;
    border: 1px solid;
    cursor: pointer;
    transition: all 0.15s ease;
  }

  .act-wave-center {
    flex: 1;
    height: 36px;
    border-radius: 999px;
    border: 1px solid;
    margin: 0 8px;
    display: flex;
    align-items: center;
    justify-content: center;
    overflow: hidden;
    transition: all 0.15s ease;
  }

  /* Inspection table */
  .inspect-table {
    width: 100%;
    margin-top: 14px;
    font-size: 11px;
    border-collapse: collapse;
  }
  .inspect-table th, .inspect-table td {
    padding: 6px 8px;
    text-align: left;
    border-bottom: 1px solid rgba(255,255,255,0.06);
  }
  .inspect-table th {
    color: var(--text-tertiary);
    font-weight: 600;
    text-transform: uppercase;
    font-size: 10px;
  }
  .swatch-inline {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    font-family: monospace;
    font-weight: 600;
  }
  .swatch-color-box {
    width: 14px;
    height: 14px;
    border-radius: 4px;
    border: 1px solid rgba(128,128,128,0.3);
  }
</style>
</head>
<body>

<header>
  <div class="header-inner">
    <div class="title-group">
      <h1>M3 Dynamic Color vs. Current Tokens</h1>
      <p>Continuous Non-Modal Tuning with Google\\'s official <code>@material/material-color-utilities</code></p>
    </div>

    <div class="controls-panel">
      <!-- Color Picker & Hex -->
      <div class="control-group">
        <label>Seed Color</label>
        <div class="seed-display-chip">
          <div class="seed-color-box" id="seedPreviewBox" style="background:#FF9248"></div>
          <input type="text" id="seedHexInput" class="hex-input" value="#FF9248">
        </div>
      </div>

      <!-- Hue Slider (0-360) -->
      <div class="slider-item">
        <div class="slider-header">
          <span>HUE</span>
          <span id="hueVal">28°</span>
        </div>
        <input type="range" id="hueSlider" min="0" max="360" value="28">
      </div>

      <!-- Chroma Slider (0-120) -->
      <div class="slider-item">
        <div class="slider-header">
          <span>CHROMA</span>
          <span id="chromaVal">65</span>
        </div>
        <input type="range" id="chromaSlider" min="0" max="120" value="65">
      </div>

      <!-- Tone Slider (10-90) -->
      <div class="slider-item">
        <div class="slider-header">
          <span>TONE</span>
          <span id="toneVal">65</span>
        </div>
        <input type="range" id="toneSlider" min="10" max="90" value="65">
      </div>

      <!-- Variant Select -->
      <div class="control-group">
        <label>Variant</label>
        <select id="variantSelect">
          <option value="TONAL_SPOT" selected>Tonal Spot (Android Standard)</option>
          <option value="VIBRANT">Vibrant (Max Chroma)</option>
          <option value="EXPRESSIVE">Expressive (Hue Shift)</option>
          <option value="FIDELITY">Fidelity (Exact Tone)</option>
          <option value="NEUTRAL">Neutral (Desaturated)</option>
        </select>
      </div>

      <!-- Contrast Select -->
      <div class="control-group">
        <label>Contrast</label>
        <select id="contrastSelect">
          <option value="0" selected>Normal (0.0)</option>
          <option value="0.5">Medium (0.5)</option>
          <option value="1.0">High (1.0)</option>
        </select>
      </div>

      <!-- Card Border Toggle -->
      <div class="control-group">
        <label>Card Border</label>
        <select id="cardBorderSelect">
          <option value="none" selected>None (App Default)</option>
          <option value="outline">1px Outline (M3 Outlined)</option>
        </select>
      </div>
    </div>
  </div>

  <!-- Presets Bar -->
  <div class="preset-row">
    <span style="font-size: 11px; color: var(--text-tertiary); font-weight: 600; text-transform: uppercase;">Presets:</span>
    <button class="preset-chip" onclick="applyPreset('#FF9248')"><span class="preset-dot" style="background:#FF9248"></span> Current Orange</button>
    <button class="preset-chip" onclick="applyPreset('#533F7A')"><span class="preset-dot" style="background:#533F7A"></span> App Purple</button>
    <button class="preset-chip" onclick="applyPreset('#1A73E8')"><span class="preset-dot" style="background:#1A73E8"></span> Pixel Blue</button>
    <button class="preset-chip" onclick="applyPreset('#34A853')"><span class="preset-dot" style="background:#34A853"></span> Android Green</button>
    <button class="preset-chip" onclick="applyPreset('#EA4335')"><span class="preset-dot" style="background:#EA4335"></span> Google Coral</button>
    <button class="preset-chip" onclick="applyPreset('#FBBC04')"><span class="preset-dot" style="background:#FBBC04"></span> Warm Amber</button>
    <button class="preset-chip" onclick="applyPreset('#00897B')"><span class="preset-dot" style="background:#00897B"></span> Mint Teal</button>
    <button class="preset-chip" onclick="applyPreset('#4A5568')"><span class="preset-dot" style="background:#4A5568"></span> Slate Gray</button>
  </div>
</header>

<div class="content-container">

  <div class="callout-box">
    <b>What to compare:</b> Slide the <b>Hue</b> or <b>Chroma</b> sliders above without closing any menus. 
    Notice how in <b>Dark Theme</b>, Google\\'s M3 turns the main accent into a soft <i>Tone-80 pastel</i> (e.g. peach or pastel lavender) to satisfy WCAG contrast rules on dark surfaces, 
    and subtly tints the card surface toward the seed color. The <b>Current Tokens</b> on the left preserve your hand-tuned vivid mid-tone (#FF9248) and dedicated 2dp border weight (#4D3627 / #CBCBCB) on the minimized pill.
    <br><br>
    <i>Note on Card Borders:</i> In the actual Android app, the expanded card has <b>no border</b> (elevation only). You can use the <b>Card Border</b> dropdown in the header to toggle between the true app style (None) and M3 Outlined (1px).
  </div>

  <div class="comparison-grid">

    <!-- COLUMN 1: CURRENT HAND-TUNED TOKENS -->
    <div class="column-card">
      <div class="column-header">
        <h2>
          <span>Current Hand-Tuned Tokens</span>
          <span class="badge-tag tag-locked">Locked (HEAD)</span>
        </h2>
        <p>Vivid mid-tone accent (#FF9248 / #EA580C) with 2dp borders and calibrated dark #1A120D container</p>
      </div>

      <div class="stage-section">
        <div class="theme-subhead">Dark Theme (Current #1A120D Container)</div>
        <div class="preview-stage dark-stage">
          <div class="pill-row-wrap">
            <div class="component-block">
              <div class="component-label">Resting Pill</div>
              <div class="min-pill" style="background: #1A120D; border-color: #4D3627;">
                <svg width="24" height="6" viewBox="0 0 24 6" fill="none" stroke="#DB7F40" stroke-width="2" stroke-linecap="round">
                  <path d="M0 3 Q6 0.5 12 3 T24 3" />
                </svg>
              </div>
            </div>

            <div class="component-block">
              <div class="component-label">Listening Pill</div>
              <div class="min-pill" style="background: #1A120D; border-color: #4D3627;">
                <svg width="24" height="12" viewBox="0 0 24 12" fill="none" stroke="#DB7F40" stroke-width="2" stroke-linecap="round">
                  <path d="M0 6 C4 2, 8 10, 12 6 S20 2, 24 6" />
                </svg>
              </div>
            </div>
          </div>

          <div class="component-block" style="width: 100%;">
            <div class="component-label">Expanded Card (210dp)</div>
            <div class="card-box" id="curDarkCardBox" style="background: #1A120D; color: #E9E6E4;">
              <div class="card-header-row">
                <div class="card-status-label" style="color: #BEBAB8;">
                  <span class="status-dot" style="background: #4ADE80;"></span>
                  Listening…
                </div>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="#7F888E">
                  <path d="M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
                </svg>
              </div>

              <div style="height: 8px;"></div>

              <div class="card-transcript-box" style="color: #E9E6E4;">
                So the plan for this week is to finish the fixture set, then move on to the benchmark harness on device. Exactly five full lines of preview text fit comfortably inside this hundred dp box without feeling cramped.
              </div>

              <div style="height: 14px;"></div>

              <div class="card-action-row">
                <div class="act-btn" style="border-color: #FF6766; color: #FF6766;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                  </svg>
                </div>

                <div class="act-wave-center" style="border-color: #BEBAB8;">
                  <svg width="120" height="28" viewBox="0 0 120 28" fill="none" stroke="#DB7F40" stroke-width="2" stroke-linecap="round">
                    <path d="M0 14 C20 4, 40 24, 60 14 S100 4, 120 14" />
                  </svg>
                </div>

                <div class="act-btn" style="border-color: #BEBAB8; color: #BEBAB8;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
                  </svg>
                </div>

                <div class="act-btn" style="background: #FF9248; border-color: #FF9248; color: #1A1207;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="theme-subhead">Light Theme (Current #FFFFFF Container)</div>
        <div class="preview-stage light-stage">
          <div class="pill-row-wrap">
            <div class="component-block">
              <div class="component-label">Resting Pill</div>
              <div class="min-pill" style="background: #FFFFFF; border-color: #CBCBCB;">
                <svg width="24" height="6" viewBox="0 0 24 6" fill="none" stroke="#D55A1A" stroke-width="2" stroke-linecap="round">
                  <path d="M0 3 Q6 0.5 12 3 T24 3" />
                </svg>
              </div>
            </div>

            <div class="component-block">
              <div class="component-label">Listening Pill</div>
              <div class="min-pill" style="background: #FFFFFF; border-color: #CBCBCB;">
                <svg width="24" height="12" viewBox="0 0 24 12" fill="none" stroke="#D55A1A" stroke-width="2" stroke-linecap="round">
                  <path d="M0 6 C4 2, 8 10, 12 6 S20 2, 24 6" />
                </svg>
              </div>
            </div>
          </div>

          <div class="component-block" style="width: 100%;">
            <div class="component-label">Expanded Card (210dp)</div>
            <div class="card-box" id="curLightCardBox" style="background: #FFFFFF; color: #2B2F31;">
              <div class="card-header-row">
                <div class="card-status-label" style="color: #5C6368;">
                  <span class="status-dot" style="background: #16A34A;"></span>
                  Listening…
                </div>
                <svg width="20" height="20" viewBox="0 0 24 24" fill="#7F888E">
                  <path d="M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
                </svg>
              </div>

              <div style="height: 8px;"></div>

              <div class="card-transcript-box" style="color: #2B2F31;">
                So the plan for this week is to finish the fixture set, then move on to the benchmark harness on device. Exactly five full lines of preview text fit comfortably inside this hundred dp box without feeling cramped.
              </div>

              <div style="height: 14px;"></div>

              <div class="card-action-row">
                <div class="act-btn" style="border-color: #E5484D; color: #E5484D;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                  </svg>
                </div>

                <div class="act-wave-center" style="border-color: #5C6368;">
                  <svg width="120" height="28" viewBox="0 0 120 28" fill="none" stroke="#D55A1A" stroke-width="2" stroke-linecap="round">
                    <path d="M0 14 C20 4, 40 24, 60 14 S100 4, 120 14" />
                  </svg>
                </div>

                <div class="act-btn" style="border-color: #5C6368; color: #5C6368;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
                  </svg>
                </div>

                <div class="act-btn" style="background: #EA580C; border-color: #EA580C; color: #FFFFFF;">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Current Table -->
        <table class="inspect-table">
          <thead>
            <tr><th>Role</th><th>Dark Hex</th><th>Light Hex</th><th>Property</th></tr>
          </thead>
          <tbody>
            <tr>
              <td>Container Fill</td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#1A120D"></i>#1A120D</span></td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#FFFFFF"></i>#FFFFFF</span></td>
              <td>Tuned warm-black / pure white (borderless)</td>
            </tr>
            <tr>
              <td>Border (Pill Only)</td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#4D3627"></i>#4D3627</span></td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#CBCBCB"></i>#CBCBCB</span></td>
              <td>2dp contour on minimized pill</td>
            </tr>
            <tr>
              <td>Accent (Accept)</td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#FF9248"></i>#FF9248</span></td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#EA580C"></i>#EA580C</span></td>
              <td>Vivid mid-tone (Tone 71 / 54)</td>
            </tr>
            <tr>
              <td>Waveform</td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#DB7F40"></i>#DB7F40</span></td>
              <td><span class="swatch-inline"><i class="swatch-color-box" style="background:#D55A1A"></i>#D55A1A</span></td>
              <td>Consistent brand wave</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>


    <!-- COLUMN 2: MATERIAL DESIGN 3 DYNAMIC SCHEME -->
    <div class="column-card">
      <div class="column-header">
        <h2>
          <span>Material Design 3 (Dynamic)</span>
          <span class="badge-tag tag-dynamic" id="activeVariantBadge">TONAL SPOT</span>
        </h2>
        <p>Live calculated from seed via HCT color science with standard Android 12-16 token mappings</p>
      </div>

      <div class="stage-section">
        <div class="theme-subhead">Dark Theme (M3 Tone-80 Accent & Tonal Surface)</div>
        <div class="preview-stage dark-stage" id="m3DarkStage">
          <div class="pill-row-wrap">
            <div class="component-block">
              <div class="component-label">Resting Pill</div>
              <div class="min-pill" id="m3DarkMinPillResting">
                <svg width="24" height="6" viewBox="0 0 24 6" fill="none" id="m3DarkRestingWave" stroke-width="2" stroke-linecap="round">
                  <path d="M0 3 Q6 0.5 12 3 T24 3" />
                </svg>
              </div>
            </div>

            <div class="component-block">
              <div class="component-label">Listening Pill</div>
              <div class="min-pill" id="m3DarkMinPillActive">
                <svg width="24" height="12" viewBox="0 0 24 12" fill="none" id="m3DarkListeningWave" stroke-width="2" stroke-linecap="round">
                  <path d="M0 6 C4 2, 8 10, 12 6 S20 2, 24 6" />
                </svg>
              </div>
            </div>
          </div>

          <div class="component-block" style="width: 100%;">
            <div class="component-label">Expanded Card (210dp)</div>
            <div class="card-box" id="m3DarkCardBox">
              <div class="card-header-row">
                <div class="card-status-label" id="m3DarkStatusLabel">
                  <span class="status-dot" id="m3DarkStatusDot"></span>
                  Listening…
                </div>
                <svg width="20" height="20" viewBox="0 0 24 24" id="m3DarkMoreIcon" fill="currentColor">
                  <path d="M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
                </svg>
              </div>

              <div style="height: 8px;"></div>

              <div class="card-transcript-box" id="m3DarkTranscript">
                So the plan for this week is to finish the fixture set, then move on to the benchmark harness on device. Exactly five full lines of preview text fit comfortably inside this hundred dp box without feeling cramped.
              </div>

              <div style="height: 14px;"></div>

              <div class="card-action-row">
                <div class="act-btn" id="m3DarkCancelBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                  </svg>
                </div>

                <div class="act-wave-center" id="m3DarkWaveCenter">
                  <svg width="120" height="28" viewBox="0 0 120 28" fill="none" id="m3DarkCardWave" stroke-width="2" stroke-linecap="round">
                    <path d="M0 14 C20 4, 40 24, 60 14 S100 4, 120 14" />
                  </svg>
                </div>

                <div class="act-btn" id="m3DarkRedoBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
                  </svg>
                </div>

                <div class="act-btn" id="m3DarkAcceptBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div class="theme-subhead">Light Theme (M3 Tone-40 Accent & Tonal Surface)</div>
        <div class="preview-stage light-stage" id="m3LightStage">
          <div class="pill-row-wrap">
            <div class="component-block">
              <div class="component-label">Resting Pill</div>
              <div class="min-pill" id="m3LightMinPillResting">
                <svg width="24" height="6" viewBox="0 0 24 6" fill="none" id="m3LightRestingWave" stroke-width="2" stroke-linecap="round">
                  <path d="M0 3 Q6 0.5 12 3 T24 3" />
                </svg>
              </div>
            </div>

            <div class="component-block">
              <div class="component-label">Listening Pill</div>
              <div class="min-pill" id="m3LightMinPillActive">
                <svg width="24" height="12" viewBox="0 0 24 12" fill="none" id="m3LightListeningWave" stroke-width="2" stroke-linecap="round">
                  <path d="M0 6 C4 2, 8 10, 12 6 S20 2, 24 6" />
                </svg>
              </div>
            </div>
          </div>

          <div class="component-block" style="width: 100%;">
            <div class="component-label">Expanded Card (210dp)</div>
            <div class="card-box" id="m3LightCardBox">
              <div class="card-header-row">
                <div class="card-status-label" id="m3LightStatusLabel">
                  <span class="status-dot" id="m3LightStatusDot"></span>
                  Listening…
                </div>
                <svg width="20" height="20" viewBox="0 0 24 24" id="m3LightMoreIcon" fill="currentColor">
                  <path d="M6 10c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm12 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm-6 0c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z"/>
                </svg>
              </div>

              <div style="height: 8px;"></div>

              <div class="card-transcript-box" id="m3LightTranscript">
                So the plan for this week is to finish the fixture set, then move on to the benchmark harness on device. Exactly five full lines of preview text fit comfortably inside this hundred dp box without feeling cramped.
              </div>

              <div style="height: 14px;"></div>

              <div class="card-action-row">
                <div class="act-btn" id="m3LightCancelBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z"/>
                  </svg>
                </div>

                <div class="act-wave-center" id="m3LightWaveCenter">
                  <svg width="120" height="28" viewBox="0 0 120 28" fill="none" id="m3LightCardWave" stroke-width="2" stroke-linecap="round">
                    <path d="M0 14 C20 4, 40 24, 60 14 S100 4, 120 14" />
                  </svg>
                </div>

                <div class="act-btn" id="m3LightRedoBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M17.65 6.35A7.958 7.958 0 0 0 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08A5.99 5.99 0 0 1 12 18c-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z"/>
                  </svg>
                </div>

                <div class="act-btn" id="m3LightAcceptBtn">
                  <svg width="26" height="26" viewBox="0 0 24 24" fill="currentColor">
                    <path d="M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z"/>
                  </svg>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- M3 Dynamic Table -->
        <table class="inspect-table">
          <thead>
            <tr><th>M3 Role</th><th>Dark Hex</th><th>Light Hex</th><th>Role Mapping</th></tr>
          </thead>
          <tbody id="m3TokensTableBody">
            <!-- Populated via JS -->
          </tbody>
        </table>
      </div>
    </div>

  </div>

</div>

<!-- Embedded Material Color Utilities Global Bundle -->
<script>
${mcuGlobalBundle}
</script>

<script>
  const MCU = window.MaterialColorUtils;
  if (!MCU) {
    console.error("MaterialColorUtils failed to initialize!");
  }

  // DOM Elements
  const seedPreviewBox = document.getElementById('seedPreviewBox');
  const seedHexInput = document.getElementById('seedHexInput');
  const hueSlider = document.getElementById('hueSlider');
  const chromaSlider = document.getElementById('chromaSlider');
  const toneSlider = document.getElementById('toneSlider');
  const hueVal = document.getElementById('hueVal');
  const chromaVal = document.getElementById('chromaVal');
  const toneVal = document.getElementById('toneVal');
  const variantSelect = document.getElementById('variantSelect');
  const contrastSelect = document.getElementById('contrastSelect');
  const cardBorderSelect = document.getElementById('cardBorderSelect');
  const variantBadge = document.getElementById('activeVariantBadge');

  let currentHct = MCU.Hct.fromInt(MCU.argbFromHex('#FF9248'));

  function syncSlidersFromHct(hct) {
    currentHct = hct;
    const h = Math.round(hct.hue);
    const c = Math.round(hct.chroma);
    const t = Math.round(hct.tone);

    hueSlider.value = h;
    chromaSlider.value = c;
    toneSlider.value = t;

    hueVal.textContent = h + '°';
    chromaVal.textContent = c;
    toneVal.textContent = t;

    const hex = MCU.hexFromArgb(hct.toInt()).toUpperCase();
    seedPreviewBox.style.background = hex;
    seedHexInput.value = hex;
  }

  function syncHctFromSliders() {
    const h = parseFloat(hueSlider.value);
    const c = parseFloat(chromaSlider.value);
    const t = parseFloat(toneSlider.value);

    hueVal.textContent = Math.round(h) + '°';
    chromaVal.textContent = Math.round(c);
    toneVal.textContent = Math.round(t);

    currentHct = MCU.Hct.from(h, c, t);
    const hex = MCU.hexFromArgb(currentHct.toInt()).toUpperCase();
    seedPreviewBox.style.background = hex;
    seedHexInput.value = hex;

    updateM3Theme();
  }

  // Sliders Input Listeners (Real-time continuous non-modal tracking)
  hueSlider.addEventListener('input', syncHctFromSliders);
  chromaSlider.addEventListener('input', syncHctFromSliders);
  toneSlider.addEventListener('input', syncHctFromSliders);

  seedHexInput.addEventListener('change', (e) => {
    let val = e.target.value.trim();
    if (!val.startsWith('#')) val = '#' + val;
    if (/^#[0-9A-Fa-f]{6}$/.test(val)) {
      const argb = MCU.argbFromHex(val);
      syncSlidersFromHct(MCU.Hct.fromInt(argb));
      updateM3Theme();
    }
  });

  variantSelect.addEventListener('change', updateM3Theme);
  contrastSelect.addEventListener('change', updateM3Theme);
  cardBorderSelect.addEventListener('change', updateM3Theme);

  function applyPreset(hex) {
    const argb = MCU.argbFromHex(hex);
    syncSlidersFromHct(MCU.Hct.fromInt(argb));
    updateM3Theme();
  }

  function createScheme(hct, isDark, variantName, contrastLevel) {
    switch(variantName) {
      case 'VIBRANT': return new MCU.SchemeVibrant(hct, isDark, contrastLevel);
      case 'EXPRESSIVE': return new MCU.SchemeExpressive(hct, isDark, contrastLevel);
      case 'FIDELITY': return new MCU.SchemeFidelity(hct, isDark, contrastLevel);
      case 'NEUTRAL': return new MCU.SchemeNeutral(hct, isDark, contrastLevel);
      case 'TONAL_SPOT':
      default:
        return new MCU.SchemeTonalSpot(hct, isDark, contrastLevel);
    }
  }

  function getHex(dynamicColor, scheme) {
    const argb = dynamicColor.getArgb(scheme);
    return MCU.hexFromArgb(argb);
  }

  function updateM3Theme() {
    const variantName = variantSelect.value;
    const contrastLevel = parseFloat(contrastSelect.value);
    const borderMode = cardBorderSelect.value;

    variantBadge.textContent = variantName.replace('_', ' ');

    const mdc = new MCU.MaterialDynamicColors();

    const darkScheme = createScheme(currentHct, true, variantName, contrastLevel);
    const lightScheme = createScheme(currentHct, false, variantName, contrastLevel);

    // Extract Dark Roles
    const dark = {
      surface: getHex(mdc.surface(), darkScheme),
      surfaceDim: getHex(mdc.surfaceDim(), darkScheme),
      surfaceContainerLowest: getHex(mdc.surfaceContainerLowest(), darkScheme),
      surfaceContainerLow: getHex(mdc.surfaceContainerLow(), darkScheme),
      surfaceContainer: getHex(mdc.surfaceContainer(), darkScheme),
      surfaceContainerHigh: getHex(mdc.surfaceContainerHigh(), darkScheme),
      surfaceContainerHighest: getHex(mdc.surfaceContainerHighest(), darkScheme),
      onSurface: getHex(mdc.onSurface(), darkScheme),
      onSurfaceVariant: getHex(mdc.onSurfaceVariant(), darkScheme),
      outline: getHex(mdc.outline(), darkScheme),
      outlineVariant: getHex(mdc.outlineVariant(), darkScheme),
      primary: getHex(mdc.primary(), darkScheme),
      onPrimary: getHex(mdc.onPrimary(), darkScheme),
      primaryContainer: getHex(mdc.primaryContainer(), darkScheme),
      onPrimaryContainer: getHex(mdc.onPrimaryContainer(), darkScheme),
      error: getHex(mdc.error(), darkScheme),
      onError: getHex(mdc.onError(), darkScheme)
    };

    // Extract Light Roles
    const light = {
      surface: getHex(mdc.surface(), lightScheme),
      surfaceDim: getHex(mdc.surfaceDim(), lightScheme),
      surfaceContainerLowest: getHex(mdc.surfaceContainerLowest(), lightScheme),
      surfaceContainerLow: getHex(mdc.surfaceContainerLow(), lightScheme),
      surfaceContainer: getHex(mdc.surfaceContainer(), lightScheme),
      surfaceContainerHigh: getHex(mdc.surfaceContainerHigh(), lightScheme),
      surfaceContainerHighest: getHex(mdc.surfaceContainerHighest(), lightScheme),
      onSurface: getHex(mdc.onSurface(), lightScheme),
      onSurfaceVariant: getHex(mdc.onSurfaceVariant(), lightScheme),
      outline: getHex(mdc.outline(), lightScheme),
      outlineVariant: getHex(mdc.outlineVariant(), lightScheme),
      primary: getHex(mdc.primary(), lightScheme),
      onPrimary: getHex(mdc.onPrimary(), lightScheme),
      primaryContainer: getHex(mdc.primaryContainer(), lightScheme),
      onPrimaryContainer: getHex(mdc.onPrimaryContainer(), lightScheme),
      error: getHex(mdc.error(), lightScheme),
      onError: getHex(mdc.onError(), lightScheme)
    };

    // Apply Dark Theme
    document.getElementById('m3DarkStage').style.background = dark.surfaceDim;
    
    // Minimized Pill (Dark): 2dp border
    const dMinRest = document.getElementById('m3DarkMinPillResting');
    dMinRest.style.background = dark.surfaceContainerLowest;
    dMinRest.style.borderColor = dark.outlineVariant;
    document.getElementById('m3DarkRestingWave').setAttribute('stroke', dark.primary);

    const dMinAct = document.getElementById('m3DarkMinPillActive');
    dMinAct.style.background = dark.surfaceContainerLowest;
    dMinAct.style.borderColor = dark.outline;
    document.getElementById('m3DarkListeningWave').setAttribute('stroke', dark.primary);

    // Expanded Cards Border Handling
    const curDarkCard = document.getElementById('curDarkCardBox');
    const curLightCard = document.getElementById('curLightCardBox');
    const dCard = document.getElementById('m3DarkCardBox');
    const lCard = document.getElementById('m3LightCardBox');

    if (borderMode === 'none') {
      curDarkCard.style.border = 'none';
      curLightCard.style.border = 'none';
      dCard.style.border = 'none';
      lCard.style.border = 'none';
    } else {
      curDarkCard.style.border = '1px solid #4D3627';
      curLightCard.style.border = '1px solid #CBCBCB';
      dCard.style.border = '1px solid ' + dark.outlineVariant;
      lCard.style.border = '1px solid ' + light.outlineVariant;
    }

    // Expanded Card (Dark)
    dCard.style.background = dark.surfaceContainer;
    document.getElementById('m3DarkStatusLabel').style.color = dark.onSurfaceVariant;
    document.getElementById('m3DarkStatusDot').style.background = dark.primary;
    document.getElementById('m3DarkMoreIcon').setAttribute('fill', dark.onSurfaceVariant);
    document.getElementById('m3DarkTranscript').style.color = dark.onSurface;

    // Action Row (Dark)
    const dCancel = document.getElementById('m3DarkCancelBtn');
    dCancel.style.borderColor = dark.error;
    dCancel.style.color = dark.error;

    const dWaveCenter = document.getElementById('m3DarkWaveCenter');
    dWaveCenter.style.borderColor = dark.outlineVariant;
    dWaveCenter.style.background = dark.surfaceContainerHigh;
    document.getElementById('m3DarkCardWave').setAttribute('stroke', dark.primary);

    const dRedo = document.getElementById('m3DarkRedoBtn');
    dRedo.style.borderColor = dark.outlineVariant;
    dRedo.style.color = dark.onSurfaceVariant;

    const dAccept = document.getElementById('m3DarkAcceptBtn');
    dAccept.style.background = dark.primary;
    dAccept.style.borderColor = dark.primary;
    dAccept.style.color = dark.onPrimary;


    // Apply Light Theme
    document.getElementById('m3LightStage').style.background = light.surfaceDim;

    // Minimized Pill (Light): 2dp border
    const lMinRest = document.getElementById('m3LightMinPillResting');
    lMinRest.style.background = light.surfaceContainerLowest;
    lMinRest.style.borderColor = light.outlineVariant;
    document.getElementById('m3LightRestingWave').setAttribute('stroke', light.primary);

    const lMinAct = document.getElementById('m3LightMinPillActive');
    lMinAct.style.background = light.surfaceContainerLowest;
    lMinAct.style.borderColor = light.outline;
    document.getElementById('m3LightListeningWave').setAttribute('stroke', light.primary);

    // Expanded Card (Light)
    lCard.style.background = light.surfaceContainer;
    document.getElementById('m3LightStatusLabel').style.color = light.onSurfaceVariant;
    document.getElementById('m3LightStatusDot').style.background = light.primary;
    document.getElementById('m3LightMoreIcon').setAttribute('fill', light.onSurfaceVariant);
    document.getElementById('m3LightTranscript').style.color = light.onSurface;

    // Action Row (Light)
    const lCancel = document.getElementById('m3LightCancelBtn');
    lCancel.style.borderColor = light.error;
    lCancel.style.color = light.error;

    const lWaveCenter = document.getElementById('m3LightWaveCenter');
    lWaveCenter.style.borderColor = light.outlineVariant;
    lWaveCenter.style.background = light.surfaceContainerHigh;
    document.getElementById('m3LightCardWave').setAttribute('stroke', light.primary);

    const lRedo = document.getElementById('m3LightRedoBtn');
    lRedo.style.borderColor = light.outlineVariant;
    lRedo.style.color = light.onSurfaceVariant;

    const lAccept = document.getElementById('m3LightAcceptBtn');
    lAccept.style.background = light.primary;
    lAccept.style.borderColor = light.primary;
    lAccept.style.color = light.onPrimary;


    // Render Tokens Table
    const tableBody = document.getElementById('m3TokensTableBody');
    const roles = [
      { name: 'primary (Accent)', dark: dark.primary, light: light.primary, mapping: 'Accept button, Waveform, Dot' },
      { name: 'on_primary', dark: dark.onPrimary, light: light.onPrimary, mapping: 'Accept button icon' },
      { name: 'surface_container', dark: dark.surfaceContainer, light: light.surfaceContainer, mapping: 'Expanded card fill' },
      { name: 'surface_container_lowest', dark: dark.surfaceContainerLowest, light: light.surfaceContainerLowest, mapping: 'Minimized pill fill' },
      { name: 'outline', dark: dark.outline, light: light.outline, mapping: 'Active pill border' },
      { name: 'outline_variant', dark: dark.outlineVariant, light: light.outlineVariant, mapping: 'Resting border, Card outline' },
      { name: 'on_surface', dark: dark.onSurface, light: light.onSurface, mapping: 'Transcript text' },
      { name: 'on_surface_variant', dark: dark.onSurfaceVariant, light: light.onSurfaceVariant, mapping: 'Status label, Redo button' },
      { name: 'error', dark: dark.error, light: light.error, mapping: 'Cancel (X) button' }
    ];

    tableBody.innerHTML = roles.map(r => \`
      <tr>
        <td><b>\${r.name}</b></td>
        <td><span class="swatch-inline"><i class="swatch-color-box" style="background:\${r.dark}"></i>\${r.dark}</span></td>
        <td><span class="swatch-inline"><i class="swatch-color-box" style="background:\${r.light}"></i>\${r.light}</span></td>
        <td style="color:var(--text-secondary)">\${r.mapping}</td>
      </tr>
    \`).join('');
  }

  // Initial Sync & Render
  syncSlidersFromHct(currentHct);
  updateM3Theme();
</script>

</body>
</html>
`;

const outputPath = path.join(__dirname, '../docs/work/visual-design/m3_palette_comparator.html');
fs.writeFileSync(outputPath, htmlContent);
console.log("Successfully rebuilt m3_palette_comparator.html with card border toggle at:", outputPath);
