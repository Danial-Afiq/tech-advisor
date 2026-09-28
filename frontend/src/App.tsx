import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom';
import './App.css';
import IngestionAdmin from './IngestionAdmin';
import {
  RequireRole,
  SessionHomeRedirect,
  SignedOutOnlyRoute,
} from './components/auth/SessionRoute';
import DevicesPageTest from './pages/DevicesPageTest';
import ThingieMagiggie from './pages/ThingieMagiggie';
import Login from './pages/Login';
import { ADMIN_HOME_PATH, LOGIN_PATH, USER_HOME_PATH } from './routing/paths';


function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/" element={<SessionHomeRedirect />} />
        <Route element={<SignedOutOnlyRoute />}>
          <Route path={LOGIN_PATH} element={<Login />} />
        </Route>
        <Route element={<RequireRole role="USER" />}>
          <Route path={USER_HOME_PATH} element={<DevicesPageTest />} />
        </Route>
        <Route element={<RequireRole role="ADMIN" />}>
          <Route path={ADMIN_HOME_PATH} element={<IngestionAdmin />} />
        </Route>

        <Route path="/DevicesPageTest" element={<Navigate to={USER_HOME_PATH} replace />} />
        <Route path="/IngestionAdmin" element={<Navigate to={ADMIN_HOME_PATH} replace />} />

        {/* This binds your component to the "/ThingieMagiggie" URL */}
        <Route path="/ThingieMagiggie" element={<ThingieMagiggie title='thingie'  />} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
