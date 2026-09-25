import { useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { signIn, signOut, signUp } from "../api/auth";
import { AuthCard } from "../components/auth/AuthCard";
import type { AuthMode } from "../components/auth/AuthCard";
import { BrandMark } from "../components/layout/BrandMark";
import { ThemeRoot } from "../components/layout/ThemeRoot";
import { Button } from "../components/ui/Button";
import { Callout } from "../components/ui/Callout";
import { Eyebrow } from "../components/ui/Eyebrow";

/** Where users land after signing up or logging in. */
const AFTER_AUTH_PATH = "/DevicesPageTest";

const HIGHLIGHTS = [
  {
    title: "Detect changes",
    text: "Launches, price shifts and new specs from changing data sources.",
  },
  {
    title: "Device-specific impact",
    text: "Your S$1,000 phone budget can be completely different from your S$2,000 laptop budget.",
  },
  {
    title: "Stay in control",
    text: "Confidence, evidence and explicit approve / dismiss feedback.",
  },
];

/**
 * Sign-up / log-in page (prototype auth screen). Sign up calls
 * `POST /api/auth/register` and then logs straight in; log in calls
 * `POST /api/auth/login`. `/login?mode=signup` opens on the Sign up tab.
 */
export default function Login() {
  const navigate = useNavigate();
  const [params] = useSearchParams();
  const [mode, setMode] = useState<AuthMode>(
    params.get("mode") === "signup" ? "signup" : "login"
  );

  const submit = async (next: AuthMode, email: string, password: string) => {
    if (next === "signup") await signUp(email, password);
    else await signIn(email, password);
    navigate(AFTER_AUTH_PATH);
  };

  const openDemo = () => {
    signOut(); // the devices page shows demo data when signed out
    navigate(AFTER_AUTH_PATH);
  };

  return (
    <ThemeRoot>
      <div className="grid min-h-full grid-cols-[1.05fr_.95fr] max-[860px]:grid-cols-1">
        {/* Hero — hidden on narrow screens, like the prototype */}
        <section className="flex flex-col justify-between border-r border-white/[0.09] p-12 [background:linear-gradient(rgba(8,16,31,.25),rgba(8,16,31,.7)),radial-gradient(circle_at_30%_30%,rgba(124,92,255,.34),transparent_36%),radial-gradient(circle_at_65%_25%,rgba(40,192,255,.16),transparent_26%)] max-[860px]:hidden">
          <BrandMark />
          <div>
            <Eyebrow>Personalised technology upgrade intelligence</Eyebrow>
            <h1 className="mx-0! mt-[22px]! mb-5! max-w-[760px] text-[clamp(40px,6vw,76px)]! leading-[.98]! font-extrabold! tracking-[-0.055em]! text-[#eef5ff]!">
              Know when to upgrade — and when not to.
            </h1>
            <p className="max-w-[720px] text-[18px] leading-[1.65] text-[#b7c4d7]">
              Tech Advisor monitors changing product releases, prices and
              specifications, then evaluates what those changes mean for each
              device you actually own.
            </p>
            <div className="mt-[34px] grid max-w-[740px] grid-cols-3 gap-3">
              {HIGHLIGHTS.map((h) => (
                <div
                  key={h.title}
                  className="rounded-[16px] border border-white/[0.09] bg-[#0e1728]/55 p-4 backdrop-blur-[12px]"
                >
                  <strong className="mb-[6px] block text-[14px]">
                    {h.title}
                  </strong>
                  <span className="text-[12px] leading-[1.45] text-[#8fa0b8]">
                    {h.text}
                  </span>
                </div>
              ))}
            </div>
          </div>
          <div className="text-[13px] text-[#8fa0b8]">
            Course-project prototype · recommendations use simulated data
          </div>
        </section>

        <section className="grid place-items-center p-7 max-[620px]:p-4">
          <AuthCard
            mode={mode}
            onModeChange={setMode}
            onSubmit={submit}
            footer={
              <>
                <Button variant="secondary" className="w-full" onClick={openDemo}>
                  Open demo account
                </Button>
                <Callout className="mt-3">
                  The demo shows a phone with a S$1,000 battery-focused upgrade
                  profile and a laptop with a S$2,000 performance-focused
                  profile. Nothing you do in the demo is saved.
                </Callout>
              </>
            }
          />
        </section>
      </div>
    </ThemeRoot>
  );
}
