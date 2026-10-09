import { BrowserRouter, Navigate, Route, Routes } from "react-router-dom";
import "./App.css";
import IngestionAdmin from "./IngestionAdmin";
import {
  RequireRole,
  SessionHomeRedirect,
  SignedOutOnlyRoute,
} from "./components/auth/SessionRoute";
import DashboardPage from "./pages/DashboardPage";
import DevicesPageTest from "./pages/DevicesPageTest";
import Login from "./pages/Login";
import ThingieMagiggie from "./pages/ThingieMagiggie";
import {
  ADMIN_HOME_PATH,
  DASHBOARD_PATH,
  DEVICES_PATH,
  LOGIN_PATH,
} from "./routing/paths";

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SessionHomeRedirect />} />

        <Route element={<SignedOutOnlyRoute />}>
          <Route path={LOGIN_PATH} element={<Login />} />
        </Route>

        <Route element={<RequireRole role="USER" />}>
          <Route path={DASHBOARD_PATH} element={<DashboardPage />} />
          <Route path={DEVICES_PATH} element={<DevicesPageTest />} />
        </Route>

        <Route element={<RequireRole role="ADMIN" />}>
          <Route
            path={ADMIN_HOME_PATH}
            element={<IngestionAdmin />}
          />
        </Route>

        <Route
          path="/DevicesPageTest"
          element={<Navigate to={DEVICES_PATH} replace />}
        />

        <Route
          path="/IngestionAdmin"
          element={<Navigate to={ADMIN_HOME_PATH} replace />}
        />

        <Route
          path="/ThingieMagiggie"
          element={<ThingieMagiggie title="thingie" />}
        />
      </Routes>
    </BrowserRouter>
  );
}

export default App;