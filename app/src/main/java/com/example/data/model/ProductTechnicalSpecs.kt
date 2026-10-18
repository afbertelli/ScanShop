package com.example.data.model

/**
 * Ficha Técnica Neural e Estrutura de Especificações Inteligentes de Produtos.
 * Abrange todas as dimensões de hardware, eletrônicos, dispositivos móveis e vestuário
 * para correspondência exata de consultas e enriquecimento visual.
 */
data class ProductTechnicalSpecs(
    // 1. Identificação Principal
    val productType: String? = null,              // Tipo de produto (ex: Placa-Mãe, Smartphone, Smartwatch, GPU, etc.)
    val brand: String? = null,                    // Marca / Fabricante (ex: Machinist, Huananzhi, Apple, Samsung)
    val model: String? = null,                    // Modelo (ex: X99, iPhone 13, Galaxy S24, RTX 4060)
    val subModelOrPartNumber: String? = null,     // Código / Submodelo / Part Number (ex: MR9A-PRO, F8, BD4, Ti 8GB)
    val releaseYear: String? = null,              // Ano de lançamento (ex: 2023, 2024)
    val colorFinish: String? = null,              // Cor / Acabamento (ex: Preto Fosco, Titânio Natural, Prata)

    // 2. Físico & Construção
    val dimensionsWeight: String? = null,         // Dimensões & Peso (ex: 244 x 244 mm, 187g)
    val buildMaterial: String? = null,            // Material de construção (ex: PCB 8 camadas, Alumínio aeronáutico, Vidro)
    val protectionRating: String? = null,         // Grau de proteção (ex: IP68, IP67, 5ATM resistente a água)
    val designErgonomics: String? = null,         // Ergonomia / Form factor (ex: ATX, Micro-ATX, Curvo, Gamer)

    // 3. Tela & Painel
    val displaySpecs: String? = null,             // Tamanho da tela, tipo de painel, resolução, taxa Hz (ex: 6.7" Super Retina XDR OLED 120Hz)

    // 4. Hardware & Desempenho
    val processorCpuGpu: String? = null,          // Processador (CPU) / Placa de Vídeo (GPU) / Chipset (ex: Intel X99 / LGA 2011-3)
    val ramMemory: String? = null,                // Memória RAM (ex: DDR4 ECC / Desktop 4 Canais Quad-Channel)
    val storageCapacity: String? = null,          // Armazenamento interno / SSD / NVMe (ex: M.2 NVMe PCIe 3.0 x4)
    val expandability: String? = null,            // Expansibilidade (ex: Suporte a até 128GB RAM, 2x Slots M.2, 6x SATA 3.0)

    // 5. Energia & Bateria
    val batterySpecs: String? = null,             // Capacidade da bateria, autonomia, potência de carregamento (ex: 5000 mAh, 67W Turbo)
    val chargingTypeVoltage: String? = null,      // Tipo de carregamento, tensão/voltagem (ex: USB-C PD, Bivolt 110V/220V)

    // 6. Conectividade & Portas
    val wirelessConnectivity: String? = null,     // Conectividade sem fio (ex: Wi-Fi 6, Bluetooth 5.3, 5G, NFC)
    val physicalPorts: String? = null,            // Portas físicas (ex: Gigabit LAN, USB 3.0, PCIe x16, Áudio 5.1)
    val simCardSlots: String? = null,             // Slots de cartão (ex: Dual SIM, Nano-SIM + eSIM)

    // 7. Software & Compatibilidade
    val operatingSystem: String? = null,          // Sistema operacional, interface, BIOS (ex: BIOS UEFI com suporte a Turbo Boost)
    val ecosystemCompatibility: String? = null,   // Compatibilidade / Ecossistema (ex: Windows 10/11, Linux, Intel Xeon E5 V3/V4)

    // 8. Câmeras & Multimídia
    val cameraSpecs: String? = null,              // Câmeras (resolução, zoom, sensores)
    val soundMicrophone: String? = null,          // Sistema de som, microfones (ex: Áudio HD Realtek ALC897, Estéreo)
    val sensorsBiometrics: String? = null,        // Sensores biométricos e de movimento (ex: Leitor de digital, GPS, Giroscópio)

    // 9. Garantia & Certificações
    val certificationsWarranty: String? = null,   // Homologação Anatel, garantia (ex: Homologado Anatel, Garantia de 12 meses)
    val packagingContents: String? = null,        // Conteúdo da embalagem (ex: Placa-Mãe, Cabo SATA, Espelho I/O, Manual)
    val ecologicalFootprint: String? = null,      // Pegada ecológica / Eficiência (ex: Selo RoHS, 80 Plus Gold)

    // 10. Metadados de IA
    val matchConfidence: Int = 90,                // Score de confiança da IA (0 a 100%)
    val keyHighlightChips: List<String> = emptyList() // Chips resumidos para exibição rápida nos cards
)
