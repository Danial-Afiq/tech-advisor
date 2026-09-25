import type {
  InputHTMLAttributes,
  ReactNode,
  SelectHTMLAttributes,
  TextareaHTMLAttributes,
} from "react";

const fieldClass =
  "w-full border-white/[0.09] bg-[#091321] text-[14px] text-[#eef5ff] focus:border-[#7c5cff]/85 focus:outline-none focus:ring-[3px] focus:ring-[#7c5cff]/12 focus-within:outline-none";

/** Label + control. `span2` makes it fill both columns of a `FormGrid`. */
export function Field({
  label,
  span2,
  children,
}: {
  label: ReactNode;
  span2?: boolean;
  children: ReactNode;
}) {
  return (
    <label
      className={`mb-[14px] flex flex-col gap-2 ${span2 ? "col-span-2 max-[620px]:col-auto" : ""}`}
    >
      <span className="text-[13px] font-semibold text-[#c6d1df]">{label}</span>
      {children}
    </label>
  );
}

/** Error message shown above a form's buttons; renders nothing when empty. */
export function FormError({ message }: { message?: string | null }) {
  if (!message) return null;
  return (
    <div
      role="alert"
      className="alert alert-error alert-soft mb-3 rounded-[12px] px-3 py-[10px] text-[13px]"
    >
      {message}
    </div>
  );
}

/** Two-column form layout that collapses to one column on phones. */
export function FormGrid({ children }: { children: ReactNode }) {
  return (
    <div className="grid grid-cols-2 gap-x-3 max-[620px]:grid-cols-1">
      {children}
    </div>
  );
}

export function Input({
  className = "",
  ...rest
}: InputHTMLAttributes<HTMLInputElement>) {
  return <input className={`input ${fieldClass} ${className}`} {...rest} />;
}

export function Select({
  options,
  className = "",
  ...rest
}: SelectHTMLAttributes<HTMLSelectElement> & { options: readonly string[] }) {
  return (
    <select className={`select ${fieldClass} ${className}`} {...rest}>
      {options.map((option) => (
        <option key={option}>{option}</option>
      ))}
    </select>
  );
}

export function Textarea({
  className = "",
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      className={`textarea min-h-[88px] ${fieldClass} ${className}`}
      {...rest}
    />
  );
}

export function Range({
  className = "",
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, "type">) {
  return (
    <input
      type="range"
      className={`range range-primary range-sm w-full ${className}`}
      {...rest}
    />
  );
}
