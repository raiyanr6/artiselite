package org.example.artiselite.service;

import org.example.artiselite.repository.ProductRepository;
import org.example.artiselite.repository.UserRepository;
import org.example.artiselite.repository.BatchRepository;
import org.example.artiselite.entity.User;
import org.example.artiselite.entity.Product;
import org.example.artiselite.entity.Batch;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.example.artiselite.dto.product.ProductResponse;
import org.example.artiselite.dto.product.ProductRequest;
import org.example.artiselite.exception.ConflictException;
import org.example.artiselite.exception.ResourceNotFoundException;
import org.example.artiselite.security.CostVisibility;


import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;





import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final BatchRepository batchRepository;
    private final UserRepository userRepository;
    private final AuditService auditService;
    private final CostVisibility costVisibility;

    @Value("${file.upload-dir}")
    private String uploadDir;

    @Transactional
    public ProductResponse createProduct(ProductRequest request) {
        if (productRepository.existsBySku(request.getSku())) {
            throw new ConflictException("SKU already exists");
        }

        Product product = Product.builder()
                .name(request.getName())
                .sku(request.getSku())
                .description(request.getDescription())
                .category(request.getCategory())
                .tags(request.getTags())
                .quantity(request.getQuantity())
                .lowStockThreshold(request.getLowStockThreshold() != null ?
                        request.getLowStockThreshold() : 10)
                .unitPrice(request.getUnitPrice() != null ?
                        request.getUnitPrice() : BigDecimal.ZERO)
                .averageCost(request.getAverageCost() != null ?
                        request.getAverageCost() : BigDecimal.ZERO)
                .isArchived(false)
                .build();

        // Generate barcode
        try {
            String barcodeFileName = generateBarcode(product.getSku());
            product.setBarcodeData(product.getSku());
            product.setBarcodeImagePath("/barcodes/" + barcodeFileName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate barcode: " + e.getMessage());
        }

        product = productRepository.save(product);

        auditService.logAction(getCurrentUserId(), "CREATE", "Product",
                product.getId(), "Product created: " + product.getName(), null);

        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public ProductResponse getProduct(Long id) {
        Product product = findActiveProduct(id);
        return mapToResponse(product);
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getAllProducts() {
        return productRepository.findActiveProducts().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> searchProducts(String keyword) {
        return productRepository.searchProducts(keyword).stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public List<ProductResponse> getLowStockProducts() {
        // Read-only: alerts are sent from the stock-changing paths (e.g. OutboundService), not on every read
        return productRepository.findLowStockProducts().stream()
                .map(this::mapToResponse)
                .collect(Collectors.toList());
    }

    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest request) {
        Product product = findActiveProduct(id);

        String oldValue = product.toString();

        product.setName(request.getName());
        product.setDescription(request.getDescription());
        product.setCategory(request.getCategory());
        product.setTags(request.getTags());
        product.setLowStockThreshold(request.getLowStockThreshold());
        product.setUnitPrice(request.getUnitPrice());

        product = productRepository.save(product);

        auditService.logAction(getCurrentUserId(), "UPDATE", "Product",
                product.getId(), "Product updated: " + product.getName(), oldValue);

        return mapToResponse(product);
    }

    @Transactional
    public void archiveProduct(Long id) {
        Product product = findActiveProduct(id);

        product.setIsArchived(true);
        productRepository.save(product);

        auditService.logAction(getCurrentUserId(), "ARCHIVE", "Product",
                product.getId(), "Product archived: " + product.getName(), null);
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = findActiveProduct(id);

        product.setIsDeleted(true);
        productRepository.save(product);

        auditService.logAction(getCurrentUserId(), "DELETE", "Product",
                product.getId(), "Product deleted: " + product.getName(), null);
    }

    // Calculate inventory valuation
    @Transactional(readOnly = true)
    public BigDecimal calculateProductValuation(Long productId) {
        Product product = findActiveProduct(productId);

        List<Batch> batches = batchRepository.findByProductIdAndQuantityGreaterThan(
                productId, 0);

        if (batches.isEmpty()) {
            return product.getAverageCost().multiply(
                    new BigDecimal(product.getQuantity()));
        }

        // Calculate weighted average cost from batches
        BigDecimal totalValue = BigDecimal.ZERO;
        int totalQuantity = 0;

        for (Batch batch : batches) {
            BigDecimal batchValue = batch.getUnitCost()
                    .multiply(new BigDecimal(batch.getQuantity()));
            totalValue = totalValue.add(batchValue);
            totalQuantity += batch.getQuantity();
        }

        return totalQuantity > 0 ? totalValue : BigDecimal.ZERO;
    }

    @Transactional(readOnly = true)
    public BigDecimal calculateTotalInventoryValuation() {
        List<Product> products = productRepository.findActiveProducts();

        return products.stream()
                .map(product -> calculateProductValuation(product.getId()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // Generate QR Code barcode
    private String generateBarcode(String data) throws WriterException, IOException {
        String fileName = UUID.randomUUID().toString() + ".png";
        Path barcodeDir = Paths.get(uploadDir, "barcodes");

        if (!Files.exists(barcodeDir)) {
            Files.createDirectories(barcodeDir);
        }

        Path barcodePath = barcodeDir.resolve(fileName);

        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(data, BarcodeFormat.QR_CODE, 300, 300);

        MatrixToImageWriter.writeToPath(bitMatrix, "PNG", barcodePath);

        return fileName;
    }

    private ProductResponse mapToResponse(Product product) {
        ProductResponse response = new ProductResponse();
        response.setId(product.getId());
        response.setName(product.getName());
        response.setSku(product.getSku());
        response.setDescription(product.getDescription());
        response.setCategory(product.getCategory());
        response.setTags(product.getTags());
        response.setQuantity(product.getQuantity());
        response.setLowStockThreshold(product.getLowStockThreshold());
        response.setUnitPrice(product.getUnitPrice());
        response.setBarcodeData(product.getBarcodeData());
        response.setBarcodeImagePath(product.getBarcodeImagePath());
        response.setIsArchived(product.getIsArchived());
        response.setIsLowStock(product.getQuantity() <= product.getLowStockThreshold());
        // Cost fields stay null for roles that may not see cost data
        if (costVisibility.canViewCostData()) {
            response.setAverageCost(product.getAverageCost());
            response.setTotalValue(calculateProductValuation(product.getId()));
        }
        response.setCreatedAt(product.getCreatedAt());
        response.setUpdatedAt(product.getUpdatedAt());

        return response;
    }

    private Product findActiveProduct(Long id) {
        return productRepository.findByIdAndIsDeletedFalse(id)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found"));
    }

    private Long getCurrentUserId() {
        String username = SecurityContextHolder.getContext().getAuthentication().getName();
        return userRepository.findByUsername(username)
                .map(User::getId)
                .orElse(null);
    }
}