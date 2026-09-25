import { useState } from 'react'
import { BrowserRouter, Routes, Route } from 'react-router-dom';
import ThingieMagiggie from './pages/ThingieMagiggie';
import Login from './pages/Login';
import './App.css'
import IngestionAdmin from './IngestionAdmin'

function App() {
  return (
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<Login/>} />
        {/* This binds your component to the "/ThingieMagiggie" URL */}
        <Route path="/ThingieMagiggie" element={<ThingieMagiggie title='thingie'  />} />
        <Route path="/IngestionAdmin" element={<IngestionAdmin/>} />
      </Routes>
    </BrowserRouter>
  );
}

export default App;
