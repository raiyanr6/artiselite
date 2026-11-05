import React, { useState } from 'react';
import { Link, useNavigate, useLocation } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import ChatPanel from './ChatPanel';
import {
  Package,
  LayoutDashboard,
  BoxIcon,
  TrendingDown,
  TrendingUp,
  Upload,
  Menu,
  X,
  LogOut,
  User,
  MessageCircle,
} from 'lucide-react';

const EMPTY_CHAT = { messages: [], conversationId: null, remainingToday: null, sending: false, pendingAction: null };

const Layout = ({ children }) => {
  const [sidebarOpen, setSidebarOpen] = useState(false);
  // Chat lives here (not in ChatPanel) so closing the panel or changing page keeps the conversation
  const [chatOpen, setChatOpen] = useState(false);
  const [chat, setChat] = useState(EMPTY_CHAT);
  const { user, logout } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();

  const handleLogout = () => {
    setChat(EMPTY_CHAT);
    setChatOpen(false);
    logout();
    navigate('/login');
  };

  const menuItems = [
    { path: '/dashboard', icon: LayoutDashboard, label: 'Dashboard' },
    { path: '/products', icon: BoxIcon, label: 'Products' },
    { path: '/inbound', icon: TrendingDown, label: 'Inbound' },
    { path: '/outbound', icon: TrendingUp, label: 'Outbound' },
    { path: '/bulk-upload', icon: Upload, label: 'Bulk Upload' },
  ];

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Sidebar */}
      <aside
        className={`fixed inset-y-0 left-0 z-50 w-64 bg-white shadow-lg transform transition-transform duration-300 ease-in-out ${
          sidebarOpen ? 'translate-x-0' : '-translate-x-full'
        } lg:translate-x-0`}
      >
        <div className="flex items-center justify-between h-16 px-6 border-b">
          <div className="flex items-center">
            <Package className="w-8 h-8 text-primary-600 mr-2" />
            <span className="text-xl font-bold text-gray-800">WMS</span>
          </div>
          <button
            onClick={() => setSidebarOpen(false)}
            className="lg:hidden text-gray-500 hover:text-gray-700"
          >
            <X className="w-6 h-6" />
          </button>
        </div>

        <nav className="p-4 space-y-2">
          {menuItems.map((item) => {
            const Icon = item.icon;
            const isActive = location.pathname === item.path;
            return (
              <Link
                key={item.path}
                to={item.path}
                className={`flex items-center px-4 py-3 rounded-lg transition-colors ${
                  isActive
                    ? 'bg-primary-100 text-primary-700'
                    : 'text-gray-700 hover:bg-gray-100'
                }`}
                onClick={() => setSidebarOpen(false)}
              >
                <Icon className="w-5 h-5 mr-3" />
                <span className="font-medium">{item.label}</span>
              </Link>
            );
          })}
        </nav>

        <div className="absolute bottom-0 w-full p-4 border-t">
          <div className="flex items-center mb-3 px-4">
            <User className="w-5 h-5 text-gray-500 mr-3" />
            <div>
              <p className="text-sm font-medium text-gray-800">{user?.fullName}</p>
              <p className="text-xs text-gray-500">{user?.role}</p>
            </div>
          </div>
          <button
            onClick={handleLogout}
            className="flex items-center w-full px-4 py-3 text-gray-700 hover:bg-gray-100 rounded-lg transition-colors"
          >
            <LogOut className="w-5 h-5 mr-3" />
            <span className="font-medium">Logout</span>
          </button>
        </div>
      </aside>

      {/* Main Content */}
      <div className="lg:ml-64">
        {/* Top Bar */}
        <header className="bg-white shadow-sm h-16 flex items-center justify-between px-6">
          <button
            onClick={() => setSidebarOpen(true)}
            className="lg:hidden text-gray-500 hover:text-gray-700"
          >
            <Menu className="w-6 h-6" />
          </button>
          <h1 className="text-xl font-semibold text-gray-800">
            Warehouse Management System
          </h1>
          <div className="flex items-center space-x-4">
            <span className="text-sm text-gray-600">Welcome, {user?.username}</span>
          </div>
        </header>

        {/* Page Content */}
        <main className="p-6">{children}</main>
      </div>

      {/* Overlay */}
      {sidebarOpen && (
        <div
          className="fixed inset-0 bg-black bg-opacity-50 z-40 lg:hidden"
          onClick={() => setSidebarOpen(false)}
        ></div>
      )}

      {/* Chat assistant: floating button (hidden while open; the panel has its own close button) */}
      {!chatOpen && (
        <button
          onClick={() => setChatOpen(true)}
          aria-label="Open assistant"
          aria-haspopup="dialog"
          className="fixed bottom-6 right-6 z-30 bg-primary-600 text-white p-4 rounded-full shadow-lg hover:bg-primary-700 transition-colors"
        >
          <MessageCircle className="w-6 h-6" />
        </button>
      )}
      {chatOpen && <ChatPanel chat={chat} setChat={setChat} onClose={() => setChatOpen(false)} />}
    </div>
  );
};

export default Layout;