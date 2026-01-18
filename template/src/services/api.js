import axios from 'axios';

const API_BASE_URL = import.meta.env.VITE_API_URL || 'http://localhost:8090/api';

const api = axios.create({
  baseURL: API_BASE_URL,
  headers: {
    'Content-Type': 'application/json',
  },
});

// Request interceptor to add token
api.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token');
    if (token) {
      config.headers.Authorization = `Bearer ${token}`;
    }
    return config;
  },
  (error) => {
    return Promise.reject(error);
  }
);

// Response interceptor for error handling
api.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token');
      localStorage.removeItem('user');
      window.location.href = '/login';
    }
    return Promise.reject(error);
  }
);

// Auth API
export const authAPI = {
  login: (credentials) => api.post('/auth/login', credentials),
  register: (userData) => api.post('/auth/register', userData),
};

// Products API
export const productsAPI = {
  getAll: () => api.get('/products'),
  getById: (id) => api.get(`/products/${id}`),
  create: (data) => api.post('/products', data),
  update: (id, data) => api.put(`/products/${id}`, data),
  delete: (id) => api.delete(`/products/${id}`),
  archive: (id) => api.patch(`/products/${id}/archive`),
  search: (keyword) => api.get(`/products/search?keyword=${keyword}`),
  getLowStock: () => api.get('/products/low-stock'),
  getValuation: (id) => api.get(`/products/${id}/valuation`),
  getTotalValuation: () => api.get('/products/valuation/total'),
};

// Inbound API
export const inboundAPI = {
  getAll: () => api.get('/inbound'),
  getById: (id) => api.get(`/inbound/${id}`),
  create: (data) => api.post('/inbound', data),
  getByDate: (date) => api.get(`/inbound/date/${date}`),
};

// Outbound API
export const outboundAPI = {
  getAll: () => api.get('/outbound'),
  getById: (id) => api.get(`/outbound/${id}`),
  create: (data) => api.post('/outbound', data),
  getByDate: (date) => api.get(`/outbound/date/${date}`),
};

// Dashboard API
export const dashboardAPI = {
  getStats: () => api.get('/dashboard'),
};

// Bulk Upload API
export const bulkUploadAPI = {
  uploadProducts: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/bulk/products', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  uploadInbound: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/bulk/inbound', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
  uploadOutbound: (file) => {
    const formData = new FormData();
    formData.append('file', file);
    return api.post('/bulk/outbound', formData, {
      headers: { 'Content-Type': 'multipart/form-data' },
    });
  },
};

export default api;