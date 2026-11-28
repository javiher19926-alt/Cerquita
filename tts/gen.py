# Narración con Kokoro (modelo abierto, licencia Apache 2.0), voz femenina en español "ef_dora".
import json, re, numpy as np, soundfile as sf
from kokoro import KPipeline
pipe = KPipeline(lang_code="e")
SR = 24000
for sid, _, text in json.load(open("tts/guion.json")):
    parts = [p for p in re.split(r"(?<=[.!?:…])\s+", text) if p.strip()]
    audio, marks, t = [], [], 0.0
    for p in parts:
        chunk = [np.asarray(a) for _, _, a in pipe(p, voice="ef_dora", speed=1.04)]
        a = np.concatenate(chunk) if chunk else np.zeros(1)
        gap = np.zeros(int(SR * (0.32 if p.endswith(("...", "…")) else 0.16)))
        marks.append([round(t, 3), round(t + len(a) / SR, 3), p])
        audio += [a, gap]; t += (len(a) + len(gap)) / SR
    sf.write(f"tts/out/{sid}.wav", np.concatenate(audio), SR)
    json.dump(marks, open(f"tts/out/{sid}.json", "w"), ensure_ascii=False)
    print(sid, round(t, 1), "s")
