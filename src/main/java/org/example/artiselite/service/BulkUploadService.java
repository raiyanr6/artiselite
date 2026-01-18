package org.example.artiselite.service;




import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;


import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.artiselite.dto.inbound.InboundRequest;
import org.example.artiselite.dto.outbound.OutboundRequest;
import org.example.artiselite.dto.product.ProductRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@RequiredArgsConstructor
public class BulkUploadService {

    private final ProductService productService;
    private final InboundService inboundService;
    private final OutboundService outboundService;
    private final NotificationService notificationService;

    @Transactional
    public Map<String, Object> uploadProducts(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        int successCount = 0;
        int failCount = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<ProductRequest> products;

            if (fileName.endsWith(".csv")) {
                products = parseProductsFromCSV(file);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                products = parseProductsFromExcel(file);
            } else {
                throw new RuntimeException("Unsupported file format. Use CSV or XLSX");
            }

            for (ProductRequest request : products) {
                try {
                    productService.createProduct(request);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    errors.add("SKU " + request.getSku() + ": " + e.getMessage());
                }
            }

            //notificationService.sendBulkUploadNotification("Product", successCount);

        } catch (Exception e) {
            errors.add("File processing error: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", successCount);
        result.put("failed", failCount);
        result.put("errors", errors);

        return result;
    }

    @Transactional
    public Map<String, Object> uploadInbound(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        int successCount = 0;
        int failCount = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<InboundRequest> inbounds;

            if (fileName.endsWith(".csv")) {
                inbounds = parseInboundFromCSV(file);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                inbounds = parseInboundFromExcel(file);
            } else {
                throw new RuntimeException("Unsupported file format. Use CSV or XLSX");
            }

            for (InboundRequest request : inbounds) {
                try {
                    inboundService.createInbound(request, null);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    errors.add("Product ID " + request.getProductId() + ": " + e.getMessage());
                }
            }

            //notificationService.sendBulkUploadNotification("Inbound", successCount);

        } catch (Exception e) {
            errors.add("File processing error: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", successCount);
        result.put("failed", failCount);
        result.put("errors", errors);

        return result;
    }

    @Transactional
    public Map<String, Object> uploadOutbound(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        int successCount = 0;
        int failCount = 0;
        List<String> errors = new ArrayList<>();

        try {
            List<OutboundRequest> outbounds;

            if (fileName.endsWith(".csv")) {
                outbounds = parseOutboundFromCSV(file);
            } else if (fileName.endsWith(".xlsx") || fileName.endsWith(".xls")) {
                outbounds = parseOutboundFromExcel(file);
            } else {
                throw new RuntimeException("Unsupported file format. Use CSV or XLSX");
            }

            for (OutboundRequest request : outbounds) {
                try {
                    outboundService.createOutbound(request, null);
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    errors.add("Product ID " + request.getProductId() + ": " + e.getMessage());
                }
            }

            //notificationService.sendBulkUploadNotification("Outbound", successCount);

        } catch (Exception e) {
            errors.add("File processing error: " + e.getMessage());
        }

        Map<String, Object> result = new HashMap<>();
        result.put("success", successCount);
        result.put("failed", failCount);
        result.put("errors", errors);

        return result;
    }

    // CSV Parsers
    private List<ProductRequest> parseProductsFromCSV(MultipartFile file)
            throws IOException, CsvException {
        List<ProductRequest> products = new ArrayList<>();

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> rows = reader.readAll();

            // Skip header row
            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);

                ProductRequest request = new ProductRequest();
                request.setName(row[0]);
                request.setSku(row[1]);
                request.setDescription(row[2]);
                request.setCategory(row[3]);
                request.setQuantity(Integer.parseInt(row[4]));
                request.setLowStockThreshold(Integer.parseInt(row[5]));
                request.setUnitPrice(new BigDecimal(row[6]));

                if (row.length > 7 && !row[7].isEmpty()) {
                    request.setTags(new HashSet<>(Arrays.asList(row[7].split(","))));
                }

                products.add(request);
            }
        }

        return products;
    }

    private List<InboundRequest> parseInboundFromCSV(MultipartFile file)
            throws IOException, CsvException {
        List<InboundRequest> inbounds = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> rows = reader.readAll();

            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);

                InboundRequest request = new InboundRequest();
                request.setProductId(Long.parseLong(row[0]));
                request.setQuantity(Integer.parseInt(row[1]));
                request.setReceivedDate(LocalDate.parse(row[2], formatter));
                request.setInvoiceReference(row[3]);
                request.setBatchNumber(row.length > 4 ? row[4] : null);
                request.setUnitCost(row.length > 5 ? new BigDecimal(row[5]) : null);

                inbounds.add(request);
            }
        }

        return inbounds;
    }

    private List<OutboundRequest> parseOutboundFromCSV(MultipartFile file)
            throws IOException, CsvException {
        List<OutboundRequest> outbounds = new ArrayList<>();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd");

        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> rows = reader.readAll();

            for (int i = 1; i < rows.size(); i++) {
                String[] row = rows.get(i);

                OutboundRequest request = new OutboundRequest();
                request.setProductId(Long.parseLong(row[0]));
                request.setQuantity(Integer.parseInt(row[1]));
                request.setDispatchDate(LocalDate.parse(row[2], formatter));
                request.setCustomerName(row[3]);
                request.setSalesOrderReference(row.length > 4 ? row[4] : null);

                outbounds.add(request);
            }
        }

        return outbounds;
    }

    // Excel Parsers
    private List<ProductRequest> parseProductsFromExcel(MultipartFile file)
            throws IOException {
        List<ProductRequest> products = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                ProductRequest request = new ProductRequest();
                request.setName(getCellStringValue(row.getCell(0)));
                request.setSku(getCellStringValue(row.getCell(1)));
                request.setDescription(getCellStringValue(row.getCell(2)));
                request.setCategory(getCellStringValue(row.getCell(3)));
                request.setQuantity((int) row.getCell(4).getNumericCellValue());
                request.setLowStockThreshold((int) row.getCell(5).getNumericCellValue());
                request.setUnitPrice(BigDecimal.valueOf(row.getCell(6).getNumericCellValue()));

                if (row.getCell(7) != null) {
                    String tags = getCellStringValue(row.getCell(7));
                    request.setTags(new HashSet<>(Arrays.asList(tags.split(","))));
                }

                products.add(request);
            }
        }

        return products;
    }

    private List<InboundRequest> parseInboundFromExcel(MultipartFile file) throws IOException {
        List<InboundRequest> inbounds = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                InboundRequest request = new InboundRequest();
                request.setProductId((long) row.getCell(0).getNumericCellValue());
                request.setQuantity((int) row.getCell(1).getNumericCellValue());
                request.setReceivedDate(row.getCell(2).getLocalDateTimeCellValue().toLocalDate());
                request.setInvoiceReference(getCellStringValue(row.getCell(3)));

                inbounds.add(request);
            }
        }

        return inbounds;
    }

    private List<OutboundRequest> parseOutboundFromExcel(MultipartFile file) throws IOException {
        List<OutboundRequest> outbounds = new ArrayList<>();

        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getSheetAt(0);

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;

                OutboundRequest request = new OutboundRequest();
                request.setProductId((long) row.getCell(0).getNumericCellValue());
                request.setQuantity((int) row.getCell(1).getNumericCellValue());
                request.setDispatchDate(row.getCell(2).getLocalDateTimeCellValue().toLocalDate());
                request.setCustomerName(getCellStringValue(row.getCell(3)));

                outbounds.add(request);
            }
        }

        return outbounds;
    }

    private String getCellStringValue(Cell cell) {
        if (cell == null) return "";

        return switch (cell.getCellType()) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> String.valueOf((int) cell.getNumericCellValue());
            default -> "";
        };
    }
}
