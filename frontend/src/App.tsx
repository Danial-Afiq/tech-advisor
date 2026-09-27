import { BrowserRouter, Routes, Route } from 'react-router-dom';
import './App.css';
import IngestionAdmin from './IngestionAdmin';
import DevicesPageTest from './pages/DevicesPageTest';
import ThingieMagiggie from './pages/ThingieMagiggie';
import Login from './pages/Login';


function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login/>} />
        {/* This binds your component to the "/ThingieMagiggie" URL */}
        <Route path="/ThingieMagiggie" element={<ThingieMagiggie title='thingie'  />} />
        <Route path="/IngestionAdmin" element={<IngestionAdmin/>} />
        <Route path="/DevicesPageTest" element={<DevicesPageTest/>} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
