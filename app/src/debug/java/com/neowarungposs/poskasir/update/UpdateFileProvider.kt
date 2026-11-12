package com.neowarungposs.poskasir.update

import androidx.core.content.FileProvider

/** Own subclass so it can sit next to the report-export FileProvider without a manifest clash. */
class UpdateFileProvider : FileProvider()
