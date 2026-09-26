import type { ReactNode } from "react";
import { Eyebrow } from "./Eyebrow";

/**
 * Page title block with an optional action on the right.
 *
 * The `!` modifiers are needed because index.css styles `h1` with unlayered
 * rules, which otherwise beat Tailwind utilities.
 */
export function PageHeader({
  eyebrow,
  title,
  description,
  action,
}: {
  eyebrow: string;
  title: string;
  description?: ReactNode;
  action?: ReactNode;
}) {
  return (
    <div className="mb-[22px] flex items-end justify-between gap-[18px] max-[860px]:flex-col max-[860px]:items-start">
      <div>
        <Eyebrow>{eyebrow}</Eyebrow>
        <h1 className="mx-0! mt-1! mb-1! text-[30px]! leading-tight! font-bold! tracking-[-0.035em]! text-[#eef5ff]! max-[620px]:text-[25px]!">
          {title}
        </h1>
        {description && (
          <p className="m-0 text-[16px] leading-[1.5] text-[#8fa0b8]">
            {description}
          </p>
        )}
      </div>
      {action}
    </div>
  );
}
