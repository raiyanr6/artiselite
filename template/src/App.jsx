// src/App.jsx
import React from 'react';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import { AuthProvider } from './context/AuthContext';
import ProtectedRoute from './components/ProtectedRoute';
import Layout from './components/Layout';
import Login from './pages/Login';
import Register from './pages/Register';
import Dashboard from './pages/Dashboard';
import Products from './pages/Products';
import Inbound from './pages/Inbound';
import Outbound from './pages/Outbound';
import BulkUpload from './pages/BulkUpload';

function App() {
  return (
    <AuthProvider>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<Login />} />
          <Route path="/register" element={<Register />} />
          
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <Layout>
                  <Dashboard />
                </Layout>
              </ProtectedRoute>
            }
          />
          
          <Route
            path="/products"
            element={
              <ProtectedRoute>
                <Layout>
                  <Products />
                </Layout>
              </ProtectedRoute>
            }
          />
          
          <Route
            path="/inbound"
            element={
              <ProtectedRoute>
                <Layout>
                  <Inbound />
                </Layout>
              </ProtectedRoute>
            }
          />
          
          <Route
            path="/outbound"
            element={
              <ProtectedRoute>
                <Layout>
                  <Outbound />
                </Layout>
              </ProtectedRoute>
            }
          />
          
          <Route
            path="/bulk-upload"
            element={
              <ProtectedRoute>
                <Layout>
                  <BulkUpload />
                </Layout>
              </ProtectedRoute>
            }
          />
          
          <Route path="/" element={<Navigate to="/dashboard" />} />
        </Routes>
      </BrowserRouter>
    </AuthProvider>
  );
}

export default App;