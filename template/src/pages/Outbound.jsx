// src/pages/Outbound.jsx
import React, { useEffect, useState } from 'react';
import { outboundAPI, productsAPI } from '../services/api';
import { Plus } from 'lucide-react';

export const Outbound = () => {
  const [outbounds, setOutbounds] = useState([]);
  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(true);
  const [showModal, setShowModal] = useState(false);
  const [formData, setFormData] = useState({
    productId: '',
    quantity: 0,
    dispatchDate: new Date().toISOString().split('T')[0],
    customerName: '',
    salesOrderReference: '',
    notes: '',
  });

  useEffect(() => {
    fetchData();
  }, []);

  const fetchData = async () => {
    try {
      const [outboundsRes, productsRes] = await Promise.all([
        outboundAPI.getAll(),
        productsAPI.getAll(),
      ]);
      setOutbounds(outboundsRes.data);
      setProducts(productsRes.data);
    } catch (error) {
      console.error('Error fetching data:', error);
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    try {
      await outboundAPI.create(formData);
      setShowModal(false);
      resetForm();
      fetchData();
    } catch (error) {
      console.error('Error creating outbound:', error);
      alert('Error: ' + (error.response?.data?.message || error.message));
    }
  };

  const resetForm = () => {
    setFormData({
      productId: '',
      quantity: 0,
      dispatchDate: new Date().toISOString().split('T')[0],
      customerName: '',
      salesOrderReference: '',
      notes: '',
    });
  };

  if (loading) {
    return <div className="flex items-center justify-center h-64"><div className="animate-spin rounded-full h-12 w-12 border-b-2 border-primary-600"></div></div>;
  }

  return (
    <div className="space-y-6">
      <div className="flex justify-between items-center">
        <h1 className="text-3xl font-bold text-gray-800">Outbound Records</h1>
        <button onClick={() => setShowModal(true)} className="btn-primary flex items-center">
          <Plus className="w-5 h-5 mr-2" />New Outbound
        </button>
      </div>

      <div className="card overflow-x-auto">
        <table className="min-w-full divide-y divide-gray-200">
          <thead className="bg-gray-50">
            <tr>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Product</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">SKU</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Quantity</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Dispatch Date</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">Customer</th>
              <th className="px-6 py-3 text-left text-xs font-medium text-gray-500 uppercase">SO Ref</th>
            </tr>
          </thead>
          <tbody className="bg-white divide-y divide-gray-200">
            {outbounds.map((outbound) => (
              <tr key={outbound.id}>
                <td className="px-6 py-4 whitespace-nowrap text-sm font-medium text-gray-900">{outbound.productName}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{outbound.productSku}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-900">{outbound.quantity}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{outbound.dispatchDate}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{outbound.customerName || '-'}</td>
                <td className="px-6 py-4 whitespace-nowrap text-sm text-gray-500">{outbound.salesOrderReference || '-'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {showModal && (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
          <div className="bg-white rounded-lg max-w-2xl w-full">
            <div className="p-6">
              <h2 className="text-2xl font-bold text-gray-800 mb-4">New Outbound</h2>
              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Product *</label>
                  <select className="input-field" value={formData.productId} onChange={(e) => setFormData({ ...formData, productId: e.target.value })} required>
                    <option value="">Select a product</option>
                    {products.map((product) => (
                      <option key={product.id} value={product.id}>{product.name} ({product.sku}) - Stock: {product.quantity}</option>
                    ))}
                  </select>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Quantity *</label>
                    <input type="number" className="input-field" value={formData.quantity} onChange={(e) => setFormData({ ...formData, quantity: parseInt(e.target.value) })} required />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Dispatch Date *</label>
                    <input type="date" className="input-field" value={formData.dispatchDate} onChange={(e) => setFormData({ ...formData, dispatchDate: e.target.value })} required />
                  </div>
                </div>
                <div className="grid grid-cols-2 gap-4">
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Customer Name</label>
                    <input type="text" className="input-field" value={formData.customerName} onChange={(e) => setFormData({ ...formData, customerName: e.target.value })} />
                  </div>
                  <div>
                    <label className="block text-sm font-medium text-gray-700 mb-1">Sales Order Ref</label>
                    <input type="text" className="input-field" value={formData.salesOrderReference} onChange={(e) => setFormData({ ...formData, salesOrderReference: e.target.value })} />
                  </div>
                </div>
                <div>
                  <label className="block text-sm font-medium text-gray-700 mb-1">Notes</label>
                  <textarea className="input-field" rows="3" value={formData.notes} onChange={(e) => setFormData({ ...formData, notes: e.target.value })}></textarea>
                </div>
                <div className="flex justify-end space-x-2 mt-6">
                  <button type="button" onClick={() => { setShowModal(false); resetForm(); }} className="btn-secondary">Cancel</button>
                  <button type="submit" className="btn-primary">Create Outbound</button>
                </div>
              </form>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Outbound