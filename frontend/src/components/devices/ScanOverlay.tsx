import { useEffect, useRef, useState } from "react";
import { Eyebrow } from "../ui/Eyebrow";
import { money } from "./deviceOptions";
import type { Device } from "./types";

const STEP_MS = 620;
const FINISH_MS = 450;

/**
 * Full-screen "Simulated AI pipeline" animation. Steps through four stages,
 * then calls `onComplete`. Mount it to start; unmounting cancels it.
 */
export function ScanOverlay({
  device,
  onComplete,
}: {
  device: Device;
  onComplete: () => void;
}) {
  const [step, setStep] = useState(0);
  const prefs = device.upgradePreferences;
  const steps = [
    `Reading launch + pricing feeds relevant to ${device.type}`,
    "Retrieving candidate product specifications",
    "Comparing against this device + its upgrade profile",
    "Generating contextual recommendation",
  ];

  // Keep the latest callback without restarting the timers on every render.
  const onCompleteRef = useRef(onComplete);
  useEffect(() => {
    onCompleteRef.current = onComplete;
  });

  useEffect(() => {
    let current = 0;
    let finish: number | undefined;
    const timer = window.setInterval(() => {
      current += 1;
      setStep(current);
      if (current >= 4) {
        window.clearInterval(timer);
        finish = window.setTimeout(() => onCompleteRef.current(), FINISH_MS);
      }
    }, STEP_MS);
    return () => {
      window.clearInterval(timer);
      window.clearTimeout(finish);
    };
  }, []);

  return (
    <div className="fixed inset-0 z-[70] grid place-items-center bg-[#02070f]/75 backdrop-blur-[7px]">
      <div className="w-[min(560px,calc(100vw-28px))] rounded-[24px] border border-white/[0.09] bg-[#0d1727] p-7 text-center shadow-[0_24px_70px_rgba(0,0,0,.33)]">
        <div className="relative mx-auto mb-5 grid h-[86px] w-[86px] place-items-center rounded-full border border-[#7c5cff]/35 bg-[radial-gradient(circle,rgba(124,92,255,.14),transparent_62%)]">
          <div className="absolute inset-[9px] animate-spin rounded-full border-2 border-transparent border-t-[#7c5cff]" />
          <span className="font-black text-[#d8d0ff]">AI</span>
        </div>
        <Eyebrow>Simulated AI pipeline</Eyebrow>
        <h3 className="mt-2 mb-[6px] text-[24px] font-bold">
          Evaluating {device.brand} {device.model}…
        </h3>
        <p className="m-0 text-[13px] text-[#8fa0b8]">
          Using this device's {money(prefs.budget)} budget and{" "}
          {prefs.priorities.slice(0, 2).join(" + ") || "saved"} priorities.
        </p>
        <div className="mt-[18px] grid gap-2 text-left">
          {steps.map((label, i) => {
            const done = i < step;
            const active = i === step;
            return (
              <div
                key={label}
                className={`flex items-center gap-[10px] text-[12px] ${
                  done
                    ? "text-[#6ee7b7]"
                    : active
                      ? "text-[#f4f7ff]"
                      : "text-[#8fa1b9]"
                }`}
              >
                {done ? "✓" : "◌"} {label}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
