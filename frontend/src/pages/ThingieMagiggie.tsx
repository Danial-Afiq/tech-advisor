// 1. Define an interface for the component's props (if any)
interface MyPageProps {
  title: string;
  isVisible?: boolean; // The '?' means this prop is optional
}

// 2. Build the functional component using the defined props
export default function ThingieMagiggie({ title, isVisible = true }: MyPageProps) {
  if (!isVisible) return null;

  return (
    <div className="page-container">
      <h1>{title}</h1>
      <p>Welcome to my Thingie page!</p>
    </div>
  );
}
