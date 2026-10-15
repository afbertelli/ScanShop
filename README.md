# ScanShop — Comparador Nativo Android de Preços (AliExpress vs. Shopee)

Aplicativo Android nativo construído com **Kotlin**, **Jetpack Compose** e **Room Database**, projetado para comparar preços em tempo real entre **AliExpress** e **Shopee**.

---

## 🚀 Funcionalidades Principais

1. **Busca e Comparação em Tempo Real (Lado a Lado & Lista Unificada)**:
   - Comparação simultânea de ofertas do AliExpress e da Shopee.
   - Alternância fluida entre modo **Lado a Lado** (colunas sincronizadas) e **Lista Unificada** (ordenada pelo menor preço).
   - Busca por voz integrada com `SpeechRecognizer` (`pt-BR`).
   - Sugestões e histórico de buscas recentes.

2. **Motor de Busca Inteligente (AI Insights)**:
   - Identificação precisa de marca, modelo, categoria e gênero (Masculino, Feminino, Infantil, Unissex).
   - Filtro anti-acessórios e anti-peças com detecção de anomalia de preço (ex: evita espelhos I/O e suportes ao buscar placas-mãe; evita cadarços ao buscar tênis).
   - Selo visual de compatibilidade e garantia de produto genuíno.

3. **Super Ofertas e Descontos**:
   - Feed de produtos com mais de 40% de desconto.
   - Filtro por marketplace (Todas as Lojas, AliExpress, Shopee).
   - Indicação de cupons ativos e frete grátis.

4. **Monitoramento e Favoritos (Room Database)**:
   - Armazenamento local com Room Database (`AliShopDatabase`).
   - Salve produtos e acompanhe variações e histórico de preços.

5. **Redirecionamento com Deep Links e Links de Afiliados**:
   - Abre diretamente no app nativo do AliExpress (`aliexpress://...`) ou Shopee (`shopee://...`).
   - Fallback automático para o navegador web com parâmetros de rastreamento de afiliados.

---

## 🛠️ Tecnologias e Arquitetura

- **Linguagem**: Kotlin 2.x
- **UI Toolkit**: Jetpack Compose com Material Design 3 (M3)
- **Banco de Dados Local**: Room Database com Kotlin Coroutines & Flow
- **Carregamento de Imagens**: Coil Compose
- **Injeção de Segredos**: Secrets Gradle Plugin (.env)
- **Testes**: JUnit 4, Robolectric 4.x
