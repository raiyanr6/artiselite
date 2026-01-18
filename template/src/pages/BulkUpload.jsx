import { Upload, FileText, CheckCircle, XCircle } from 'lucide-react';
import { bulkUploadAPI } from '../services/api';
import React from 'react';
import { useState } from 'react';

export const BulkUpload = () => {
  const [selectedFile, setSelectedFile] = useState(null);
  const [uploadType, setUploadType] = useState('products');
  const [uploading, setUploading] = useState(false);
  const [result, setResult] = useState(null);

  const handleFileChange = (e) => {
    setSelectedFile(e.target.files[0]);
    setResult(null);
  };

  const handleUpload = async () => {
    if (!selectedFile) {
      alert('Please select a file');
      return;
    }

    setUploading(true);
    setResult(null);

    try {
      let response;
      if (uploadType === 'products') {
        response = await bulkUploadAPI.uploadProducts(selectedFile);
      } else if (uploadType === 'inbound') {
        response = await bulkUploadAPI.uploadInbound(selectedFile);
      } else {
        response = await bulkUploadAPI.uploadOutbound(selectedFile);
      }
      setResult(response.data);
    } catch (error) {
      setResult({ success: 0, failed: 1, errors: [error.response?.data?.message || error.message] });
    } finally {
      setUploading(false);
      setSelectedFile(null);
    }
  };

  return (
    <div className="space-y-6">
      <h1 className="text-3xl font-bold text-gray-800">Bulk Upload</h1>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="card">
          <h2 className="text-xl font-semibold text-gray-800 mb-4">Upload File</h2>
          <div className="space-y-4">
            <div>
              <label className="block text-sm font-medium text-gray-700 mb-1">Upload Type</label>
              <select className="input-field" value={uploadType} onChange={(e) => setUploadType(e.target.value)}>
                <option value="products">Products</option>
                <option value="inbound">Inbound</option>
                <option value="outbound">Outbound</option>
              </select>
            </div>

            <div>
              <label className="block text-sm font-medium text-gray-700 mb-2">Select CSV/XLSX File</label>
              <div className="border-2 border-dashed border-gray-300 rounded-lg p-6 text-center">
                <input type="file" accept=".csv,.xlsx,.xls" onChange={handleFileChange} className="hidden" id="file-upload" />
                <label htmlFor="file-upload" className="cursor-pointer">
                  <Upload className="w-12 h-12 text-gray-400 mx-auto mb-2" />
                  <p className="text-sm text-gray-600">Click to select file or drag and drop</p>
                  <p className="text-xs text-gray-500 mt-1">CSV or XLSX files only</p>
                </label>
                {selectedFile && (
                  <div className="mt-4 flex items-center justify-center text-sm text-gray-700">
                    <FileText className="w-4 h-4 mr-2" />
                    {selectedFile.name}
                  </div>
                )}
              </div>
            </div>

            <button onClick={handleUpload} disabled={!selectedFile || uploading} className="w-full btn-primary">
              {uploading ? 'Uploading...' : 'Upload File'}
            </button>
          </div>
        </div>

        <div className="card">
          <h2 className="text-xl font-semibold text-gray-800 mb-4">CSV Format Guidelines</h2>
          
          {uploadType === 'products' && (
            <div className="bg-gray-50 p-4 rounded-lg">
              <h3 className="font-medium text-gray-800 mb-2">Products CSV Columns:</h3>
              <code className="text-xs block bg-white p-3 rounded">
                name,sku,description,category,quantity,lowStockThreshold,unitPrice,tags
              </code>
              <p className="text-sm text-gray-600 mt-2">Example:</p>
              <code className="text-xs block bg-white p-3 rounded mt-1">
                Laptop,SKU001,Description,Electronics,100,10,999.99,laptop;tech
              </code>
            </div>
          )}

          {uploadType === 'inbound' && (
            <div className="bg-gray-50 p-4 rounded-lg">
              <h3 className="font-medium text-gray-800 mb-2">Inbound CSV Columns:</h3>
              <code className="text-xs block bg-white p-3 rounded">
                productId,quantity,receivedDate,invoiceReference,batchNumber,unitCost
              </code>
              <p className="text-sm text-gray-600 mt-2">Example:</p>
              <code className="text-xs block bg-white p-3 rounded mt-1">
                1,50,2024-01-15,INV-001,BATCH-001,45.00
              </code>
            </div>
          )}

          {uploadType === 'outbound' && (
            <div className="bg-gray-50 p-4 rounded-lg">
              <h3 className="font-medium text-gray-800 mb-2">Outbound CSV Columns:</h3>
              <code className="text-xs block bg-white p-3 rounded">
                productId,quantity,dispatchDate,customerName,salesOrderReference
              </code>
              <p className="text-sm text-gray-600 mt-2">Example:</p>
              <code className="text-xs block bg-white p-3 rounded mt-1">
                1,20,2024-01-16,ABC Company,SO-001
              </code>
            </div>
          )}
        </div>
      </div>

      {result && (
        <div className="card">
          <h2 className="text-xl font-semibold text-gray-800 mb-4">Upload Results</h2>
          <div className="grid grid-cols-2 gap-4 mb-4">
            <div className="bg-green-50 p-4 rounded-lg flex items-center">
              <CheckCircle className="w-8 h-8 text-green-600 mr-3" />
              <div>
                <p className="text-sm text-gray-600">Successful</p>
                <p className="text-2xl font-bold text-green-600">{result.success}</p>
              </div>
            </div>
            <div className="bg-red-50 p-4 rounded-lg flex items-center">
              <XCircle className="w-8 h-8 text-red-600 mr-3" />
              <div>
                <p className="text-sm text-gray-600">Failed</p>
                <p className="text-2xl font-bold text-red-600">{result.failed}</p>
              </div>
            </div>
          </div>
          {result.errors && result.errors.length > 0 && (
            <div className="mt-4">
              <h3 className="font-medium text-gray-800 mb-2">Errors:</h3>
              <div className="bg-red-50 border border-red-200 rounded-lg p-4 max-h-60 overflow-y-auto">
                {result.errors.map((error, index) => (
                  <p key={index} className="text-sm text-red-700 mb-1">• {error}</p>
                ))}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
};

export default BulkUpload