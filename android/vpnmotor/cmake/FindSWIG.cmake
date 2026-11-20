# Upstream CMakeLists.txt yapılandırma aşamasında SWIG arıyor, ancak SWIG yalnızca
# kullanmadığımız OpenVPN3 hedefi (ovpn3) için gerekli. Bu sahte bulucu yapılandırmanın
# geçmesini sağlar; build.gradle.kts'deki "targets" listesi ovpn3'ü zaten derlemez.
set(SWIG_FOUND TRUE)
set(SWIG_VERSION "4.0.0")
set(SWIG_EXECUTABLE "swig-kullanilmiyor")
set(SWIG_DIR "")
