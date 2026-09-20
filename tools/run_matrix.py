import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bench_runner import select_asr, select_llm, select_fixture, execute_benchmark

def run_suite(tests):
    print(f"Starting test suite ({len(tests)} runs)...")
    results = []
    for i, (asr, llm, fixture) in enumerate(tests, 1):
        print(f"\n==================================================")
        print(f"[{i}/{len(tests)}] Running: ASR={asr} | LLM={llm} | Fixture={fixture}")
        print(f"==================================================")
        
        ok = select_asr(asr)
        if not ok:
            print(f"Failed to select ASR: {asr}")
            continue
            
        ok = select_llm(llm)
        if not ok:
            print(f"Failed to select LLM: {llm}")
            continue
            
        ok = select_fixture(fixture)
        if not ok:
            print(f"Failed to select fixture: {fixture}")
            continue
            
        res = execute_benchmark(timeout_sec=180)
        if res:
            results.append((asr, llm, fixture, res))
        else:
            print(f"FAILED run: {asr} + {llm} on {fixture}")
        time.sleep(2)
        
    print(f"\nSuite complete. {len(results)}/{len(tests)} succeeded.")
    return results

if __name__ == "__main__":
    batch = sys.argv[1] if len(sys.argv) > 1 else "all"
    
    # 1. ASR Comparison: Test all 6 ASR models with Gemma 4 E2B
    asr_models = [
        "Nemotron.*320ms",
        "Nemotron.*560ms",
        "Whisper Base",
        "Whisper Small",
        "Parakeet TDT",
        "Whisper Large",
    ]
    
    fixtures = ["Short", "Medium", "Long"]
    
    if batch == "asr_short":
        tests = [(asr, "Gemma 4 E2B", "Short") for asr in asr_models]
        run_suite(tests)
    elif batch == "asr_medium":
        tests = [(asr, "Gemma 4 E2B", "Medium") for asr in asr_models]
        run_suite(tests)
    elif batch == "asr_long":
        tests = [(asr, "Gemma 4 E2B", "Long") for asr in asr_models]
        run_suite(tests)
    elif batch == "llm_compare":
        # Compare E2B, E4B, and Gemma 3 1B on Nemotron 560ms
        llms = ["Gemma 4 E2B", "Gemma 4 E4B", "Gemma 3 1B"]
        tests = [("Nemotron.*560ms", llm, f) for llm in llms for f in fixtures]
        run_suite(tests)
    elif batch == "e4b_compare":
        tests = [("Nemotron.*560ms", "Gemma 4 E4B", f) for f in ["Medium", "Long"]]
        run_suite(tests)
    elif batch == "low_spec":
        # Test Whisper Base + Gemma 3 1B across all 3 fixtures
        tests = [("Whisper Base", "Gemma 3 1B", f) for f in fixtures]
        run_suite(tests)
    elif batch == "full_asr":
        tests = [(asr, "Gemma 4 E2B", f) for f in fixtures for asr in asr_models]
        run_suite(tests)
    else:
        print("Usage: python tools/run_matrix.py [asr_short | asr_medium | asr_long | llm_compare | low_spec | full_asr]")
