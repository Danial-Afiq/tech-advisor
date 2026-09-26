import { useState } from "react";
import { Button } from "../ui/Button";
import { Field, FormError, Input } from "../ui/Form";
import { Modal, ModalActions } from "../ui/Modal";

/**
 * Email + password sign-in dialog. `onSignIn` does the request; if it
 * rejects, its message is shown in the form.
 */
export function SignInModal({
  onClose,
  onSignIn,
}: {
  onClose: () => void;
  onSignIn: (email: string, password: string) => Promise<void>;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (event: { preventDefault: () => void }) => {
    event.preventDefault();
    setError(null);
    setBusy(true);
    try {
      await onSignIn(email, password);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not sign in.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      eyebrow="Your account"
      title="Sign in to save devices"
      width="max-w-[470px]"
      onClose={onClose}
    >
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
        <Field label="Password">
          <Input
            type="password"
            required
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </Field>
        <FormError message={error} />
        <ModalActions>
          <Button variant="ghost" onClick={onClose}>
            Cancel
          </Button>
          <Button type="submit" variant="primary" loading={busy}>
            Sign in
          </Button>
        </ModalActions>
      </form>
    </Modal>
  );
}
