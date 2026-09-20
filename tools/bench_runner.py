import subprocess
import time
import xml.etree.ElementTree as ET
import re
import sys

def adb_cmd(cmd):
    return subprocess.run(f"adb {cmd}", shell=True, capture_output=True, text=True)

def dump_ui():
    adb_cmd("shell uiautomator dump /sdcard/ui.xml")
    res = subprocess.run("adb shell cat /sdcard/ui.xml", shell=True, capture_output=True, encoding='utf-8', errors='ignore')
    xml_str = res.stdout
    try:
        return ET.fromstring(xml_str)
    except Exception as e:
        print(f"Error parsing XML: {e}")
        return None

def find_bounds(root, text_pattern=None, desc_pattern=None):
    if root is None:
        return None
    for elem in root.iter():
        text = elem.attrib.get('text', '')
        desc = elem.attrib.get('content-desc', '')
        match = True
        if text_pattern and not re.search(text_pattern, text, re.IGNORECASE):
            match = False
        if desc_pattern and not re.search(desc_pattern, desc, re.IGNORECASE):
            match = False
        if match:
            bounds = elem.attrib.get('bounds')
            if bounds:
                m = re.match(r'\[(\d+),(\d+)\]\[(\d+),(\d+)\]', bounds)
                if m:
                    x1, y1, x2, y2 = map(int, m.groups())
                    return (x1 + x2) // 2, (y1 + y2) // 2
    return None

def scroll_to_top():
    for _ in range(3):
        adb_cmd("shell input swipe 600 500 600 2200 150")
        time.sleep(0.3)

def scroll_to_bottom():
    for _ in range(2):
        adb_cmd("shell input swipe 600 2200 600 800 200")
        time.sleep(0.4)

def select_mode(mode_name):
    # Mode buttons are near the top
    scroll_to_top()
    time.sleep(0.5)
    root = dump_ui()
    coords = find_bounds(root, text_pattern=f"^{mode_name}")
    if coords:
        print(f"Selecting mode '{mode_name}' at {coords}")
        adb_cmd(f"shell input tap {coords[0]} {coords[1]}")
        time.sleep(0.6)
    else:
        print(f"Warning: Mode '{mode_name}' not found")

def select_fixture(fixture_name):
    scroll_to_top()
    time.sleep(0.4)
    root = dump_ui()
    coords = find_bounds(root, desc_pattern="Select Audio Fixture")
    if not coords:
        print("Error: 'Select Audio Fixture' button not found")
        return False
    adb_cmd(f"shell input tap {coords[0]} {coords[1]}")
    time.sleep(0.6)
    root = dump_ui()
    item_coords = find_bounds(root, text_pattern=fixture_name)
    if item_coords:
        print(f"Selecting fixture '{fixture_name}' at {item_coords}")
        adb_cmd(f"shell input tap {item_coords[0]} {item_coords[1]}")
        time.sleep(0.5)
        return True
    else:
        print(f"Error: fixture item '{fixture_name}' not found")
        adb_cmd("shell input tap 100 500")
        return False

def select_asr(asr_name):
    scroll_to_top()
    time.sleep(0.4)
    root = dump_ui()
    coords = find_bounds(root, desc_pattern="Select ASR model")
    if not coords:
        print("Error: 'Select ASR model' button not found")
        return False
    adb_cmd(f"shell input tap {coords[0]} {coords[1]}")
    time.sleep(0.6)
    root = dump_ui()
    item_coords = find_bounds(root, text_pattern=asr_name)
    if item_coords:
        print(f"Selecting ASR '{asr_name}' at {item_coords}")
        adb_cmd(f"shell input tap {item_coords[0]} {item_coords[1]}")
        time.sleep(0.5)
        return True
    else:
        print(f"Error: ASR item '{asr_name}' not found")
        adb_cmd("shell input tap 100 500")
        return False

def select_llm(llm_name):
    scroll_to_top()
    time.sleep(0.4)
    root = dump_ui()
    coords = find_bounds(root, desc_pattern="Select LLM model")
    if not coords:
        print("Error: 'Select LLM model' button not found")
        return False
    adb_cmd(f"shell input tap {coords[0]} {coords[1]}")
    time.sleep(0.6)
    root = dump_ui()
    item_coords = find_bounds(root, text_pattern=llm_name)
    if item_coords:
        print(f"Selecting LLM '{llm_name}' at {item_coords}")
        adb_cmd(f"shell input tap {item_coords[0]} {item_coords[1]}")
        time.sleep(0.5)
        return True
    else:
        print(f"Error: LLM item '{llm_name}' not found")
        adb_cmd("shell input tap 100 500")
        return False

def scroll_to_run_button():
    scroll_to_top()
    time.sleep(0.3)
    adb_cmd("shell input swipe 600 1800 600 1000 250")
    time.sleep(0.5)

def execute_benchmark(timeout_sec=180):
    scroll_to_run_button()
    root = dump_ui()
    coords = find_bounds(root, text_pattern="Run Benchmark")
    if not coords:
        print("Retrying finding Run Benchmark button with minor adjustment...")
        adb_cmd("shell input swipe 600 1600 600 1200 250")
        time.sleep(0.5)
        root = dump_ui()
        coords = find_bounds(root, text_pattern="Run Benchmark")
    if not coords:
        print("Error: 'Run Benchmark' button not found")
        return None
    
    # Clear logcat
    adb_cmd("logcat -c")
    print(f"Tapping 'Run Benchmark' at {coords}...")
    adb_cmd(f"shell input tap {coords[0]} {coords[1]}")
    
    start_time = time.time()
    csv_line = None
    gt_line = None
    raw_line = None
    cl_line = None
    
    while time.time() - start_time < timeout_sec:
        time.sleep(2.0)
        logs = subprocess.run("adb logcat -d -s SmartVoiceBenchmark:V", shell=True, capture_output=True, encoding='utf-8', errors='ignore').stdout
        for line in logs.splitlines():
            if "CSV:" in line and not csv_line:
                csv_line = line.split("CSV:", 1)[1].strip()
            if "GroundTruth:" in line and not gt_line:
                gt_line = line.split("GroundTruth:", 1)[1].strip()
            if "Raw ASR:" in line and not raw_line:
                raw_line = line.split("Raw ASR:", 1)[1].strip()
            if "Cleaned:" in line and not cl_line:
                cl_line = line.split("Cleaned:", 1)[1].strip()
        if csv_line:
            break
        print(f"  Waiting for benchmark completion... ({int(time.time() - start_time)}s)")
        
    if csv_line:
        print("=== Benchmark Finished! ===")
        print(f"CSV: {csv_line}")
        print(f"Ground Truth : {gt_line}")
        print(f"Raw ASR      : {raw_line}")
        print(f"Cleaned      : {cl_line}")
        return {
            "csv": csv_line,
            "gt": gt_line,
            "raw": raw_line,
            "cleaned": cl_line
        }
    else:
        print("Error: Benchmark timed out!")
        return None

if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "test":
        select_asr("Nemotron.*320ms")
        select_llm("Gemma 4 E2B")
        select_fixture("Short")
        execute_benchmark()
