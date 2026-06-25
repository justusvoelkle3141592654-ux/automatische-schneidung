from flask import Flask, render_template, request, jsonify, send_file
import os
import uuid
import subprocess
import threading
import json
from pathlib import Path

app = Flask(__name__)
app.config['MAX_CONTENT_LENGTH'] = 2 * 1024 * 1024 * 1024  # 2 GB

UPLOAD_FOLDER = Path('uploads')
OUTPUT_FOLDER = Path('output')
UPLOAD_FOLDER.mkdir(exist_ok=True)
OUTPUT_FOLDER.mkdir(exist_ok=True)

jobs: dict = {}
jobs_lock = threading.Lock()

ALLOWED = {'.mp4', '.mov', '.avi', '.mkv', '.webm', '.m4v', '.mts', '.ts'}

COLOR_FILTERS = {
    'normal':     '',
    'cinematic':  'eq=brightness=-0.05:contrast=1.3:saturation=0.75,colorbalance=rs=-0.05:gs=-0.02:bs=0.1',
    'vintage':    'eq=brightness=0.05:contrast=0.9:saturation=0.55,colorbalance=rs=0.12:gs=0.04:bs=-0.08',
    'vivid':      'eq=brightness=0.05:contrast=1.15:saturation=1.6',
    'dramatisch': 'eq=brightness=-0.12:contrast=1.6:saturation=0.35',
    'warm':       'colorbalance=rs=0.15:gs=0.05:bs=-0.12,eq=saturation=1.2:contrast=1.05',
    'cool':       'colorbalance=rs=-0.1:gs=0.02:bs=0.18,eq=saturation=1.1:contrast=1.1',
}

PRESETS = {
    'cinematic':  {'color': 'cinematic',  'transition': 'dissolve',  'duration': 8,  'speed': 1.0},
    'energetisch':{'color': 'vivid',      'transition': 'wipeleft',  'duration': 4,  'speed': 1.25},
    'vintage':    {'color': 'vintage',    'transition': 'fade',      'duration': 10, 'speed': 0.9},
    'modern':     {'color': 'normal',     'transition': 'slideleft', 'duration': 6,  'speed': 1.0},
    'dramatisch': {'color': 'dramatisch', 'transition': 'dissolve',  'duration': 7,  'speed': 1.0},
    'warm':       {'color': 'warm',       'transition': 'circleopen','duration': 9,  'speed': 0.95},
}


def _probe(path: Path) -> dict:
    r = subprocess.run(
        ['ffprobe', '-v', 'quiet', '-print_format', 'json',
         '-show_streams', '-show_format', str(path)],
        capture_output=True, text=True
    )
    return json.loads(r.stdout) if r.returncode == 0 else {}


def get_duration(path: Path) -> float:
    info = _probe(path)
    try:
        return float(info['format']['duration'])
    except (KeyError, TypeError, ValueError):
        for s in info.get('streams', []):
            try:
                return float(s['duration'])
            except (KeyError, ValueError):
                pass
    return 0.0


def has_audio_stream(path: Path) -> bool:
    info = _probe(path)
    return any(s.get('codec_type') == 'audio' for s in info.get('streams', []))


def generate_thumbnail(video: Path, out: Path) -> None:
    dur = get_duration(video)
    seek = min(1.0, dur * 0.1) if dur > 0 else 0
    subprocess.run([
        'ffmpeg', '-ss', str(seek), '-i', str(video),
        '-vframes', '1', '-s', '320x180', '-y', str(out)
    ], capture_output=True)


def process_clip(src: Path, dst: Path, color: str, dur: float, speed: float) -> bool:
    """Normalize one clip: scale, color grade, fade, speed."""
    fade_dur = min(0.5, dur / 5)
    fade_frames = max(1, int(fade_dur * 30))
    fade_out_start = max(0, int((dur - fade_dur) * 30))

    vf_parts = [
        'scale=1280:720:force_original_aspect_ratio=decrease,'
        'pad=1280:720:(ow-iw)/2:(oh-ih)/2:black'
    ]
    if speed != 1.0:
        vf_parts.append(f'setpts={1/speed:.4f}*PTS')
    cf = COLOR_FILTERS.get(color, '')
    if cf:
        vf_parts.append(cf)
    vf_parts.append(f'fade=in:0:{fade_frames}')
    vf_parts.append(f'fade=out:{fade_out_start}:{fade_frames}')
    vf = ','.join(vf_parts)

    af_parts = []
    if speed != 1.0:
        af_parts.append(f'atempo={min(2.0, max(0.5, speed)):.4f}')
    af_parts.append(f'afade=in:st=0:d={fade_dur:.3f}')
    af_parts.append(f'afade=out:st={max(0, dur - fade_dur):.3f}:d={fade_dur:.3f}')
    af = ','.join(af_parts)

    audio_in = has_audio_stream(src)
    cmd = ['ffmpeg']
    if audio_in:
        cmd += ['-i', str(src)]
        map_flags = []
    else:
        cmd += ['-i', str(src), '-f', 'lavfi', '-i', 'anullsrc=r=44100:cl=stereo']
        map_flags = ['-map', '0:v', '-map', '1:a']

    cmd += ['-t', str(dur)]
    if map_flags:
        cmd += map_flags
    cmd += [
        '-r', '30',
        '-vf', vf,
        '-af', af,
        '-c:v', 'libx264', '-preset', 'fast', '-crf', '23',
        '-c:a', 'aac', '-ar', '44100', '-b:a', '128k',
        '-y', str(dst)
    ]
    r = subprocess.run(cmd, capture_output=True)
    return r.returncode == 0


def concat_clips(clips: list[Path], out: Path, transition: str) -> bool:
    """Concatenate processed clips with xfade transitions."""
    if len(clips) == 1:
        import shutil
        shutil.copy(clips[0], out)
        return True

    durations = [get_duration(p) for p in clips]
    trans_dur = 0.5

    inputs = []
    for p in clips:
        inputs += ['-i', str(p)]

    v_prev = '[0:v]'
    a_prev = '[0:a]'
    filters = []
    offset = 0.0

    for i in range(1, len(clips)):
        offset += durations[i - 1] - trans_dur
        is_last = (i == len(clips) - 1)
        v_out = '[vout]' if is_last else f'[v{i}]'
        a_out = '[aout]' if is_last else f'[a{i}]'

        filters.append(
            f'{v_prev}[{i}:v]xfade=transition={transition}:'
            f'duration={trans_dur}:offset={offset:.3f}{v_out}'
        )
        filters.append(
            f'{a_prev}[{i}:a]acrossfade=d={trans_dur}{a_out}'
        )
        v_prev = v_out
        a_prev = a_out

    cmd = ['ffmpeg'] + inputs + [
        '-filter_complex', ';'.join(filters),
        '-map', '[vout]', '-map', '[aout]',
        '-c:v', 'libx264', '-preset', 'fast', '-crf', '23',
        '-c:a', 'aac',
        '-y', str(out)
    ]
    r = subprocess.run(cmd, capture_output=True)
    return r.returncode == 0


def _set_status(job_id: str, **kwargs) -> None:
    with jobs_lock:
        jobs[job_id].update(kwargs)


def process_job(job_id: str, filenames: list[str], preset_name: str) -> None:
    try:
        preset = PRESETS.get(preset_name, PRESETS['cinematic'])
        color     = preset['color']
        transition = preset['transition']
        max_dur   = float(preset['duration'])
        speed     = float(preset['speed'])

        job_dir = UPLOAD_FOLDER / job_id
        out_path = OUTPUT_FOLDER / f'{job_id}.mp4'

        _set_status(job_id, status='processing', progress=5, message='Starte Verarbeitung…')

        processed: list[Path] = []
        for i, name in enumerate(filenames):
            src = job_dir / name
            dst = job_dir / f'clip_{i:03d}.mp4'

            pct = 5 + int((i / len(filenames)) * 75)
            _set_status(job_id, progress=pct,
                        message=f'Verarbeite Clip {i+1} von {len(filenames)}…')

            actual = get_duration(src)
            clip_dur = min(max_dur, actual) if actual > 0 else max_dur

            if process_clip(src, dst, color, clip_dur, speed):
                processed.append(dst)
            else:
                _set_status(job_id, status='error', progress=0,
                            message=f'Fehler bei Clip {i+1}.')
                return

        _set_status(job_id, progress=85, message='Füge alle Clips zusammen…')

        if concat_clips(processed, out_path, transition):
            _set_status(job_id, status='done', progress=100,
                        message='Fertig! Dein Video ist bereit.')
        else:
            _set_status(job_id, status='error', progress=0,
                        message='Fehler beim Zusammenfügen.')

    except Exception as exc:
        _set_status(job_id, status='error', progress=0, message=f'Fehler: {exc}')


# ── Routes ────────────────────────────────────────────────────────────────────

@app.route('/')
def index():
    return render_template('index.html')


@app.route('/upload', methods=['POST'])
def upload():
    files = request.files.getlist('videos')
    if not files:
        return jsonify(error='Keine Videos angegeben.'), 400
    if len(files) > 20:
        return jsonify(error='Maximal 20 Videos erlaubt.'), 400

    job_id = str(uuid.uuid4())
    job_dir = UPLOAD_FOLDER / job_id
    job_dir.mkdir(parents=True, exist_ok=True)

    saved = []
    for f in files:
        if not f.filename:
            continue
        ext = Path(f.filename).suffix.lower()
        if ext not in ALLOWED:
            continue
        stem = Path(f.filename).stem[:40]
        idx = len(saved)
        safe = f'{idx:03d}_{stem}{ext}'
        dest = job_dir / safe
        f.save(str(dest))

        thumb = job_dir / f'thumb_{idx}.jpg'
        generate_thumbnail(dest, thumb)

        saved.append({'original': f.filename, 'saved': safe,
                      'thumb_url': f'/thumbnail/{job_id}/{idx}'})

    if not saved:
        return jsonify(error='Keine gültigen Videodateien gefunden.'), 400

    with jobs_lock:
        jobs[job_id] = {
            'status': 'uploaded',
            'progress': 0,
            'message': f'{len(saved)} Video(s) hochgeladen.',
            'files': [s['saved'] for s in saved],
        }

    return jsonify(job_id=job_id, files=saved)


@app.route('/thumbnail/<job_id>/<int:idx>')
def thumbnail(job_id, idx):
    p = UPLOAD_FOLDER / job_id / f'thumb_{idx}.jpg'
    if p.exists():
        return send_file(str(p), mimetype='image/jpeg')
    return '', 404


@app.route('/process', methods=['POST'])
def start_process():
    data = request.get_json(force=True)
    job_id = data.get('job_id', '')
    preset = data.get('preset', 'cinematic')

    with jobs_lock:
        job = jobs.get(job_id)
    if not job:
        return jsonify(error='Ungültige Job-ID.'), 400
    if job['status'] == 'processing':
        return jsonify(error='Job läuft bereits.'), 400

    filenames = job.get('files', [])
    if not filenames:
        return jsonify(error='Keine hochgeladenen Videos.'), 400

    t = threading.Thread(target=process_job, args=(job_id, filenames, preset), daemon=True)
    t.start()
    return jsonify(status='started')


@app.route('/status/<job_id>')
def status(job_id):
    with jobs_lock:
        job = jobs.get(job_id)
    if not job:
        return jsonify(error='Job nicht gefunden.'), 404
    return jsonify(job)


@app.route('/download/<job_id>')
def download(job_id):
    p = OUTPUT_FOLDER / f'{job_id}.mp4'
    if not p.exists():
        return jsonify(error='Video nicht verfügbar.'), 404
    return send_file(str(p), as_attachment=True,
                     download_name='automatisch_geschnitten.mp4',
                     mimetype='video/mp4')


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=False)
