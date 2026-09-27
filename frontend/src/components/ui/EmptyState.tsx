import type { ReactNode } from "react";
import { Card } from "./Card";

/** Centred placeholder shown when a list has nothing in it. */
export function EmptyState({
  icon,
  title,
  description,
  action,
}: {
  icon: ReactNode;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <Card>
      <div className="card-body items-center px-[18px] py-[50px] text-center text-[#8fa0b8]">
        <div className="mb-2 text-[44px]">{icon}</div>
        <strong className="text-[#eef5ff]">{title}</strong>
        {description && (
          <div className="mt-[7px] mb-[14px] text-[13px]">{description}</div>
        )}
        {action}
      </div>
    </Card>
  );
}
