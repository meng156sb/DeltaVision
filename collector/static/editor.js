function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

class FrameEditor {
  constructor(canvas) {
    this.canvas = canvas;
    this.context = canvas.getContext('2d');
    this.textarea = document.getElementById(canvas.dataset.textareaId);
    this.resetButton = document.getElementById(canvas.dataset.resetId);
    this.image = new Image();
    this.boxes = [];
    this.originalBoxes = [];
    this.selectedIndex = -1;
    this.mode = null;
    this.anchor = null;

    this.resetButton?.addEventListener('click', () => {
      this.boxes = this.originalBoxes.map((box) => ({ ...box }));
      this.selectedIndex = -1;
      this.syncTextarea();
      this.render();
    });

    this.canvas.addEventListener('mousedown', (event) => this.onPointerDown(event));
    this.canvas.addEventListener('mousemove', (event) => this.onPointerMove(event));
    window.addEventListener('mouseup', () => this.onPointerUp());
    this.canvas.addEventListener('dblclick', (event) => this.onDoubleClick(event));
    this.textarea?.addEventListener('change', () => this.loadFromTextarea());

    this.loadFromTextarea();
    this.image.onload = () => {
      const ratio = this.image.height / this.image.width;
      this.canvas.width = this.image.width;
      this.canvas.height = this.image.height;
      this.canvas.style.aspectRatio = `${this.image.width} / ${this.image.height}`;
      this.render();
    };
    this.image.src = canvas.dataset.imageSrc;
  }

  loadFromTextarea() {
    try {
      const parsed = JSON.parse(this.textarea?.value || '[]');
      this.boxes = parsed.map((box) => ({
        left: Number(box.left ?? box.left_px ?? 0),
        top: Number(box.top ?? box.top_px ?? 0),
        right: Number(box.right ?? box.right_px ?? 0),
        bottom: Number(box.bottom ?? box.bottom_px ?? 0),
        confidence: Number(box.confidence ?? 0.9),
        label: box.label || 'person_body',
        trackId: Number(box.trackId ?? box.track_id ?? -1),
      }));
      this.originalBoxes = this.boxes.map((box) => ({ ...box }));
      this.render();
    } catch (error) {
      console.warn('invalid boxes json', error);
    }
  }

  syncTextarea() {
    if (!this.textarea) return;
    this.textarea.value = JSON.stringify(this.boxes, null, 2);
  }

  getCanvasPoint(event) {
    const rect = this.canvas.getBoundingClientRect();
    const scaleX = this.canvas.width / rect.width;
    const scaleY = this.canvas.height / rect.height;
    return {
      x: (event.clientX - rect.left) * scaleX,
      y: (event.clientY - rect.top) * scaleY,
    };
  }

  hitTest(point) {
    for (let index = this.boxes.length - 1; index >= 0; index -= 1) {
      const box = this.boxes[index];
      if (point.x >= box.left && point.x <= box.right && point.y >= box.top && point.y <= box.bottom) {
        return index;
      }
    }
    return -1;
  }

  onPointerDown(event) {
    const point = this.getCanvasPoint(event);
    const hitIndex = this.hitTest(point);
    if (hitIndex >= 0) {
      this.selectedIndex = hitIndex;
      const box = this.boxes[hitIndex];
      this.mode = 'move';
      this.anchor = {
        x: point.x,
        y: point.y,
        left: box.left,
        top: box.top,
        right: box.right,
        bottom: box.bottom,
      };
    } else {
      this.selectedIndex = this.boxes.length;
      this.mode = 'create';
      this.anchor = point;
      this.boxes.push({
        left: point.x,
        top: point.y,
        right: point.x,
        bottom: point.y,
        confidence: 0.9,
        label: 'person_body',
        trackId: -1,
      });
    }
    this.render();
  }

  onPointerMove(event) {
    if (this.selectedIndex < 0 || !this.mode) return;
    const point = this.getCanvasPoint(event);
    const box = this.boxes[this.selectedIndex];
    if (!box) return;

    if (this.mode === 'move' && this.anchor) {
      const width = this.anchor.right - this.anchor.left;
      const height = this.anchor.bottom - this.anchor.top;
      let nextLeft = this.anchor.left + (point.x - this.anchor.x);
      let nextTop = this.anchor.top + (point.y - this.anchor.y);
      nextLeft = clamp(nextLeft, 0, this.canvas.width - width);
      nextTop = clamp(nextTop, 0, this.canvas.height - height);
      box.left = nextLeft;
      box.top = nextTop;
      box.right = nextLeft + width;
      box.bottom = nextTop + height;
    }

    if (this.mode === 'create' && this.anchor) {
      box.left = clamp(Math.min(this.anchor.x, point.x), 0, this.canvas.width);
      box.top = clamp(Math.min(this.anchor.y, point.y), 0, this.canvas.height);
      box.right = clamp(Math.max(this.anchor.x, point.x), 0, this.canvas.width);
      box.bottom = clamp(Math.max(this.anchor.y, point.y), 0, this.canvas.height);
    }

    this.syncTextarea();
    this.render();
  }

  onPointerUp() {
    if (this.selectedIndex >= 0) {
      const box = this.boxes[this.selectedIndex];
      if (box && (box.right - box.left < 4 || box.bottom - box.top < 4)) {
        this.boxes.splice(this.selectedIndex, 1);
        this.selectedIndex = -1;
      }
      this.syncTextarea();
      this.render();
    }
    this.mode = null;
    this.anchor = null;
  }

  onDoubleClick(event) {
    const point = this.getCanvasPoint(event);
    const hitIndex = this.hitTest(point);
    if (hitIndex < 0) return;
    this.boxes.splice(hitIndex, 1);
    this.selectedIndex = -1;
    this.syncTextarea();
    this.render();
  }

  render() {
    const context = this.context;
    context.clearRect(0, 0, this.canvas.width, this.canvas.height);
    if (this.image.complete) {
      context.drawImage(this.image, 0, 0, this.canvas.width, this.canvas.height);
    }

    this.boxes.forEach((box, index) => {
      const selected = index === this.selectedIndex;
      context.strokeStyle = selected ? '#22c55e' : '#ef4444';
      context.lineWidth = selected ? 3 : 2;
      context.strokeRect(box.left, box.top, box.right - box.left, box.bottom - box.top);
      const caption = `${box.label} ${box.confidence.toFixed(2)}`;
      context.font = '18px sans-serif';
      const textWidth = context.measureText(caption).width;
      context.fillStyle = selected ? '#22c55e' : '#ef4444';
      context.fillRect(box.left, Math.max(0, box.top - 24), textWidth + 12, 24);
      context.fillStyle = '#ffffff';
      context.fillText(caption, box.left + 6, Math.max(18, box.top - 6));
    });
  }
}

document.querySelectorAll('.review-canvas').forEach((canvas) => {
  new FrameEditor(canvas);
});
