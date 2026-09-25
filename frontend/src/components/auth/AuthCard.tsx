import { useState } from "react";
import type { ReactNode } from "react";
import { MIN_PASSWORD_LENGTH } from "../../api/auth";
import { Button } from "../ui/Button";
import { Eyebrow } from "../ui/Eyebrow";
import { Field, FormError, Input } from "../ui/Form";

export type AuthMode = "signup" | "login";

const COPY: Record<
  AuthMode,
  { title: string; blurb: string; submit: string }
> = {
  signup: {
    title: "Create your account",
    blurb:
      "Add the devices you own and define a separate upgrade profile for each one.",
    submit: "Create account",
  },
  login: {
    title: "Welcome back",
    blurb: "Sign in to view your monitored devices and recommendations.",
    submit: "Log in",
  },
};

/**
 * Sign-up / log-in card with daisyUI tabs. `onSubmit` does the request; if it
 * rejects, the message is shown in the form. `footer` renders under a divider
 * (e.g. a demo-account button).
 */
export function AuthCard({
  mode,
  onModeChange,
  onSubmit,
  footer,
}: {
  mode: AuthMode;
  onModeChange: (mode: AuthMode) => void;
  onSubmit: (mode: AuthMode, email: string, password: string) => Promise<void>;
  footer?: ReactNode;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const copy = COPY[mode];

  const switchMode = (next: AuthMode) => {
    setError(null);
    onModeChange(next);
  };

  const submit = async (event: { preventDefault: () => void }) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await onSubmit(mode, email, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="w-full max-w-[470px] rounded-[26px] border border-white/[0.09] bg-[#0e1728]/80 p-[30px] shadow-[0_24px_70px_rgba(0,0,0,.33)] backdrop-blur-[18px] max-[620px]:p-5">
      <Eyebrow>Welcome to Tech Advisor</Eyebrow>
      <h2 className="mx-0! mt-2! mb-1! text-[28px]! leading-tight! font-bold! tracking-[-0.02em]! text-[#eef5ff]!">
        {copy.title}
      </h2>
      <p className="mt-[6px] text-[13px] leading-[1.55] text-[#8fa0b8]">
        {copy.blurb}
      </p>

      {/* daisyUI: tabs */}
      <div
        role="tablist"
        className="tabs tabs-box my-6 grid grid-cols-2 rounded-[14px] border border-white/[0.09] bg-[#0a1323] p-1"
      >
        {(["signup", "login"] as const).map((tab) => (
          <button
            key={tab}
            type="button"
            role="tab"
            aria-selected={mode === tab}
            onClick={() => switchMode(tab)}
            className={`tab h-auto rounded-[10px] px-3 py-[10px] text-[14px] font-bold ${
              mode === tab
                ? "tab-active bg-[#16243b] text-[#eef5ff]"
                : "text-[#8fa0b8]"
            }`}
          >
            {tab === "signup" ? "Sign up" : "Log in"}
          </button>
        ))}
      </div>

      <form onSubmit={submit}>
        <Field label="Email">
          <Input
            type="email"
            required
            autoComplete="email"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
            placeholder="you@example.com"
          />
        </Field>
        <Field
          label={
            mode === "signup"
              ? `Password (at least ${MIN_PASSWORD_LENGTH} characters)`
              : "Password"
          }
        >
          <Input
            type="password"
            required
            minLength={mode === "signup" ? MIN_PASSWORD_LENGTH : undefined}
            autoComplete={mode === "signup" ? "new-password" : "current-password"}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder="••••••••"
          />
        </Field>
        <FormError message={error} />
        <Button type="submit" variant="primary" loading={busy} className="w-full">
          {copy.submit}
        </Button>
      </form>

      {footer && (
        <>
          <div className="my-[18px] h-px bg-white/[0.09]" />
          {footer}
        </>
      )}
    </div>
  );
}
