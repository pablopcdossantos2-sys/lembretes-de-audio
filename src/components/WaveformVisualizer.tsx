import React, { useEffect, useRef } from 'react';

interface WaveformVisualizerProps {
  amplitude: number; // 0 to 1
  isRecording: boolean;
  color?: string;
  height?: number;
}

export const WaveformVisualizer: React.FC<WaveformVisualizerProps> = ({
  amplitude,
  isRecording,
  color = '#8b5cf6',
  height = 80
}) => {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const barsRef = useRef<number[]>(Array(36).fill(0.1));

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;

    let animId: number;

    const render = () => {
      ctx.clearRect(0, 0, canvas.width, canvas.height);

      if (isRecording) {
        // Shift bars and push new amplitude with some variation
        barsRef.current.shift();
        const noise = (Math.random() - 0.5) * 0.15;
        const barVal = Math.max(0.08, Math.min(1.0, amplitude * 1.5 + noise));
        barsRef.current.push(barVal);
      } else {
        // Idle gentle wave
        barsRef.current.shift();
        barsRef.current.push(0.08 + Math.sin(Date.now() / 400) * 0.04);
      }

      const barCount = barsRef.current.length;
      const barWidth = (canvas.width / barCount) * 0.65;
      const gap = (canvas.width / barCount) * 0.35;
      const centerY = canvas.height / 2;

      barsRef.current.forEach((val, i) => {
        const x = i * (barWidth + gap);
        const barH = Math.max(4, val * (canvas.height - 12));
        const y = centerY - barH / 2;

        const gradient = ctx.createLinearGradient(0, y, 0, y + barH);
        gradient.addColorStop(0, '#c084fc');
        gradient.addColorStop(1, color);

        ctx.fillStyle = gradient;
        ctx.beginPath();
        ctx.roundRect(x, y, barWidth, barH, 4);
        ctx.fill();
      });

      animId = requestAnimationFrame(render);
    };

    render();

    return () => {
      cancelAnimationFrame(animId);
    };
  }, [amplitude, isRecording, color]);

  return (
    <div className="w-full flex items-center justify-center bg-slate-950/60 rounded-xl p-3 border border-slate-800">
      <canvas
        ref={canvasRef}
        width={380}
        height={height}
        className="w-full max-w-md h-[80px]"
      />
    </div>
  );
};
