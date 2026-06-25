'use strict';

const $ = id => document.getElementById(id);

let jobId = null;
let pollTimer = null;

// ── Section visibility ─────────────────────────────────────────────────────
function showStep(id) {
  document.querySelectorAll('.step').forEach(s => s.classList.remove('active'));
  const el = document.getElementById(id);
  if (el) { el.classList.remove('hidden'); el.classList.add('active'); }
}

// ── Upload ─────────────────────────────────────────────────────────────────
const dropZone  = $('drop-zone');
const fileInput = $('file-input');

dropZone.addEventListener('dragover', e => {
  e.preventDefault();
  dropZone.classList.add('drag-over');
});

dropZone.addEventListener('dragleave', () => dropZone.classList.remove('drag-over'));

dropZone.addEventListener('drop', e => {
  e.preventDefault();
  dropZone.classList.remove('drag-over');
  const files = [...e.dataTransfer.files].filter(f => f.type.startsWith('video/'));
  if (files.length) uploadFiles(files);
});

dropZone.addEventListener('click', () => fileInput.click());
fileInput.addEventListener('change', e => uploadFiles([...e.target.files]));

$('add-more-btn').addEventListener('click', () => fileInput.click());

$('next-btn').addEventListener('click', () => showStep('step-style'));
$('back-btn').addEventListener('click', () => showStep('step-upload'));
$('restart-btn').addEventListener('click', () => {
  jobId = null;
  $('video-grid').innerHTML = '';
  $('video-grid').classList.add('hidden');
  $('upload-actions').classList.add('hidden');
  $('upload-progress-wrap').classList.add('hidden');
  showStep('step-upload');
});

$('process-btn').addEventListener('click', startProcessing);

// Preset card highlighting
document.querySelectorAll('.preset-card').forEach(card => {
  card.addEventListener('click', () => {
    document.querySelectorAll('.preset-card').forEach(c =>
      c.querySelector('input').checked = false
    );
    card.querySelector('input').checked = true;
  });
});

function uploadFiles(files) {
  if (files.length > 20) {
    alert('Maximal 20 Videos erlaubt.');
    files = files.slice(0, 20);
  }

  const formData = new FormData();
  files.forEach(f => formData.append('videos', f));

  const wrap  = $('upload-progress-wrap');
  const bar   = $('upload-bar');
  const label = $('upload-label');
  wrap.classList.remove('hidden');
  bar.style.width = '0%';
  label.textContent = 'Hochladen…';

  const xhr = new XMLHttpRequest();
  xhr.open('POST', '/upload');

  xhr.upload.addEventListener('progress', e => {
    if (e.lengthComputable) {
      const pct = Math.round((e.loaded / e.total) * 100);
      bar.style.width = pct + '%';
      label.textContent = `${pct}% hochgeladen`;
    }
  });

  xhr.addEventListener('load', () => {
    if (xhr.status === 200) {
      const data = JSON.parse(xhr.responseText);
      jobId = data.job_id;
      bar.style.width = '100%';
      label.textContent = `${data.files.length} Video(s) hochgeladen ✓`;
      renderThumbnails(data.files);
    } else {
      const err = JSON.parse(xhr.responseText);
      alert('Fehler beim Hochladen: ' + (err.error || xhr.status));
      wrap.classList.add('hidden');
    }
  });

  xhr.addEventListener('error', () => {
    alert('Netzwerkfehler beim Hochladen.');
    wrap.classList.add('hidden');
  });

  xhr.send(formData);
}

function renderThumbnails(files) {
  const grid = $('video-grid');
  grid.innerHTML = '';

  files.forEach(f => {
    const card = document.createElement('div');
    card.className = 'video-card';

    if (f.thumb_url) {
      const img = document.createElement('img');
      img.className = 'video-thumb';
      img.src = f.thumb_url;
      img.alt = f.original;
      img.onerror = () => img.replaceWith(placeholder());
      card.appendChild(img);
    } else {
      card.appendChild(placeholder());
    }

    const name = document.createElement('div');
    name.className = 'video-card-name';
    name.textContent = f.original;
    card.appendChild(name);

    grid.appendChild(card);
  });

  grid.classList.remove('hidden');
  $('upload-actions').classList.remove('hidden');
}

function placeholder() {
  const div = document.createElement('div');
  div.className = 'video-thumb-placeholder';
  div.innerHTML = `<svg width="32" height="32" viewBox="0 0 32 32" fill="none">
    <rect x="2" y="6" width="28" height="20" rx="3" stroke="currentColor" stroke-width="1.5"/>
    <path d="M13 11l8 5-8 5V11z" fill="currentColor"/>
  </svg>`;
  return div;
}

// ── Processing ─────────────────────────────────────────────────────────────
function selectedPreset() {
  const checked = document.querySelector('input[name="preset"]:checked');
  return checked ? checked.value : 'cinematic';
}

function startProcessing() {
  if (!jobId) { alert('Bitte zuerst Videos hochladen.'); return; }

  showStep('step-processing');
  updateProcessingUI(0, 'Starte Verarbeitung…');

  fetch('/process', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ job_id: jobId, preset: selectedPreset() })
  })
    .then(r => r.json())
    .then(d => {
      if (d.error) { alert(d.error); showStep('step-style'); return; }
      startPolling();
    })
    .catch(() => { alert('Verbindungsfehler.'); showStep('step-style'); });
}

function startPolling() {
  if (pollTimer) clearInterval(pollTimer);
  pollTimer = setInterval(pollStatus, 1500);
}

function pollStatus() {
  if (!jobId) { clearInterval(pollTimer); return; }

  fetch(`/status/${jobId}`)
    .then(r => r.json())
    .then(d => {
      if (d.error) { clearInterval(pollTimer); return; }

      updateProcessingUI(d.progress || 0, d.message || '');

      if (d.status === 'done') {
        clearInterval(pollTimer);
        showDone();
      } else if (d.status === 'error') {
        clearInterval(pollTimer);
        alert('Fehler: ' + d.message);
        showStep('step-style');
      }
    })
    .catch(() => { /* network blip, keep polling */ });
}

function updateProcessingUI(pct, msg) {
  $('progress-bar').style.width = pct + '%';
  $('progress-pct').textContent  = pct + '%';
  $('processing-msg').textContent = msg;
  if (pct >= 80) $('processing-title').textContent = 'Fast fertig…';
  else if (pct >= 50) $('processing-title').textContent = 'Füge alles zusammen…';
  else $('processing-title').textContent = 'Verarbeite Videos…';
}

function showDone() {
  $('download-link').href = `/download/${jobId}`;
  showStep('step-done');
}
